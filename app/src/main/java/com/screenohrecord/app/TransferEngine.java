package com.screenohrecord.app;

import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** A completed local video is removed only after an exact destination read-back succeeds. */
final class TransferEngine {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private TransferEngine() { }

    static boolean begin() { return BUSY.compareAndSet(false, true); }
    static void end() { BUSY.set(false); }
    static boolean busy() { return BUSY.get(); }

    interface Observer { void progress(String message, long bytes, long total); }

    private static final class Progress {
        private final Context context;
        private final Observer observer;
        private final int totalFiles;
        private long lastReport;
        private String phase = "";
        private String name = "";
        private int index;

        Progress(Context context, Observer observer, int totalFiles) {
            this.context = context; this.observer = observer; this.totalFiles = totalFiles;
        }

        void file(RecordingStore.Entry entry) { name = entry.name; index++; phase = ""; }

        void update(String nextPhase, long bytes, long total) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (nextPhase.equals(phase) && now - lastReport < 1000 && bytes != total) return;
            phase = nextPhase; lastReport = now;
            String message = "File " + index + " of " + totalFiles + " · " + name + "\n" + phase;
            if (total > 0) message += String.format(java.util.Locale.ROOT, " · %.1f / %.1f MB",
                    bytes / 1048576.0, total / 1048576.0);
            status(context, message);
            observer.progress(message, bytes, total);
        }
    }

    static boolean eligible(RecordingStore.Entry entry, String destination, long now, int minutes) {
        return entry.completedAt > 0 && now >= entry.completedAt
                && now - entry.completedAt >= minutes * 60_000L && RecordingStore.sameDirectory(destination, entry.destination);
    }

    static int run(Context context, BooleanSupplier cancelled) throws IOException {
        return run(context, cancelled, (message, bytes, total) -> { });
    }

    static int run(Context context, BooleanSupplier cancelled, Observer observer) throws IOException {
        TransferSettings.Config config = TransferSettings.load(context);
        if (!config.enabled) return 0;
        String destination = RecordingOptions.get(context).getString("folder_uri", "");
        BooleanSupplier stopped = () -> cancelled.getAsBoolean()
                || !TransferSettings.preferences(context).getBoolean("enabled", false)
                || !config.revision.equals(TransferSettings.preferences(context).getString("revision", ""))
                || !destination.equals(RecordingOptions.get(context).getString("folder_uri", ""));
        RecordingStore store = new RecordingStore(context);
        store.scanTransferFolder();
        java.util.List<RecordingStore.Entry> files = new java.util.ArrayList<>();
        for (RecordingStore.Entry entry : store.transferEntries()) {
            if (!store.isUnfinished(entry.uri)
                    && eligible(entry, destination, System.currentTimeMillis(), config.delayMinutes)) files.add(entry);
        }
        Progress progress = new Progress(context, observer, files.size());
        TransferSettings.preferences(context).edit().putBoolean("retry_needed", false).apply();
        int moved = 0;
        int failed = 0;
        IOException firstFailure = null;
        // Open no network connection when there are no completed, old-enough recordings.
        TransferConnection remote = null;
        try {
            for (RecordingStore.Entry entry : files) {
                if (stopped.getAsBoolean()) throw new IOException("Transfer paused. Local recordings retained");
                if (store.isUnfinished(entry.uri)) continue;
                if (!eligible(entry, destination, System.currentTimeMillis(), config.delayMinutes)) continue;
                progress.file(entry);
                progress.update("Connecting", 0, entry.bytes);
                try {
                    RecordingStore.Availability availability = store.availability(entry);
                    if (availability == RecordingStore.Availability.MISSING) { store.delete(entry); continue; }
                    if (availability != RecordingStore.Availability.PRESENT)
                        throw new IOException("Restore access to the video folder to resume transfers");
                    for (int attempt = 0; ; attempt++) {
                        try {
                            if (remote == null) remote = new TransferConnection(context, config, stopped);
                            move(context, store, entry, remote, progress, config);
                            break;
                        } catch (IOException error) {
                            if (stopped.getAsBoolean() || !recoverable(error) || attempt >= 2) throw error;
                            android.util.Log.w("ScreenOhRecord", "Connection interrupted; resuming current video", error);
                            if (remote != null) { remote.close(); remote = null; }
                            progress.update("Connection interrupted. Resuming", 0, entry.bytes);
                            for (int pause = 0; pause < 10; pause++) {
                                if (stopped.getAsBoolean()) throw error;
                                android.os.SystemClock.sleep(100);
                            }
                        }
                    }
                    moved++;
                    status(context, "Moved " + moved + " video(s). Local copies removed after verification.");
                } catch (IOException | RuntimeException error) {
                    if (stopped.getAsBoolean() || TransferConnection.wifi(context) == null)
                        throw new IOException("Transfer interrupted at " + entry.name + ". Saved progress will resume. "
                                + error.getMessage(), error);
                    failed++;
                    if (firstFailure == null) firstFailure = new IOException(entry.name + ": " + error.getMessage(), error);
                    android.util.Log.w("ScreenOhRecord", "Video transfer failed; continuing the folder batch", error);
                    if (remote != null) { remote.close(); remote = null; }
                }
            }
        } finally { if (remote != null) remote.close(); }
        if (failed > 0) {
            TransferSettings.preferences(context).edit().putBoolean("retry_needed", true).apply();
            String result = "Moved " + moved + " video(s). " + failed + " video(s) retained for retry. " + firstFailure.getMessage();
            issue(context, result);
            if (moved == 0) throw new IOException(result, firstFailure);
            return moved;
        }
        if (moved == 0) status(context, "Checked recordings. Waiting for the selected delay and Wi-Fi.");
        return moved;
    }

    static void status(Context context, String message) {
        TransferSettings.preferences(context).edit().putString("status", message)
                .putLong("checked_at", System.currentTimeMillis()).apply();
    }

    static void issue(Context context, String message) {
        TransferSettings.preferences(context).edit().putString("last_issue", message)
                .putLong("last_issue_at", System.currentTimeMillis()).apply();
        status(context, message);
    }

    private static boolean recoverable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketException || cause instanceof java.io.InterruptedIOException
                    || cause instanceof java.io.EOFException
                    || cause instanceof org.apache.commons.net.ftp.FTPConnectionClosedException) return true;
        }
        return false;
    }

    private static void move(Context context, RecordingStore store, RecordingStore.Entry entry,
            TransferConnection remote, Progress progress, TransferSettings.Config config) throws IOException {
        progress.update("Checking local video", 0, entry.bytes);
        Digest local;
        try (InputStream input = source(context, entry.uri)) { local = digest(input, remote); }
        if (local.bytes <= 0) throw new IOException("Empty recording retained locally");
        String name = remoteName(context, entry, local.sha256);
        String temporary = name + ".uploading";
        String checkpointPrefix = hex(hash().digest((config.protocol + "/" + config.host + ":" + config.port
                + "/" + config.share + "/" + config.folder + "/" + config.username + "/" + name)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!remote.exists(name)) {
            upload(context, entry, remote, local, temporary, progress);
            try { verifyResumable(context, remote, temporary, local, checkpointPrefix + ".temporary", progress); }
            catch (ChecksumException error) {
                remote.remove(temporary);
                throw error;
            }
            remote.rename(temporary, name);
        }
        // Also verifies a final file left by a previous run that could not remove the local copy.
        verifyResumable(context, remote, name, local, checkpointPrefix + ".final", progress);
        progress.update("Rechecking local video before deletion", 0, local.bytes);
        Digest current;
        try (InputStream input = source(context, entry.uri)) { current = digest(input, remote); }
        if (!local.equals(current)) throw new IOException("Local recording changed during transfer. Local file retained");
        long modified = store.fileModifiedAt(entry.uri);
        if (modified > 0 && modified != entry.completedAt)
            throw new IOException("Video modification time changed during transfer. Local file retained for a fresh delay");
        remote.check();
        if (store.isUnfinished(entry.uri)) throw new IOException("The active recording must stay on the device");
        try { store.delete(entry); }
        catch (RuntimeException error) { throw new IOException("Remote copy verified. Restore folder access to remove the local copy", error); }
        context.getSharedPreferences("transfer_verification", Context.MODE_PRIVATE).edit()
                .remove(checkpointPrefix + ".temporary").remove(checkpointPrefix + ".final").apply();
    }

    private static void skip(InputStream input, long offset, TransferConnection remote) throws IOException {
        while (offset > 0) {
            remote.check();
            long skipped = input.skip(offset);
            if (skipped == 0) {
                if (input.read() == -1) throw new IOException("Local video changed before resuming upload");
                skipped = 1;
            }
            offset -= skipped;
        }
    }

    private static void upload(Context context, RecordingStore.Entry entry, TransferConnection remote,
            Digest local, String temporary, Progress progress) throws IOException {
        long offset = Math.max(0, remote.size(temporary));
        if (offset > local.bytes) { remote.remove(temporary); offset = 0; }
        progress.update(offset > 0 ? "Resuming upload" : "Uploading", offset, local.bytes);
        while (offset < local.bytes) {
            long started = android.os.SystemClock.elapsedRealtime();
            try (InputStream input = source(context, entry.uri)) {
                skip(input, offset, remote);
                try (OutputStream output = remote.write(temporary, offset)) {
                    byte[] buffer = new byte[65536];
                    while (offset < local.bytes) {
                        remote.check();
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, local.bytes - offset));
                        if (read < 0) throw new IOException("Local video changed during upload");
                        output.write(buffer, 0, read);
                        offset += read;
                        progress.update("Uploading", offset, local.bytes);
                        // Finish a short FTP command regularly so its control connection stays active.
                        if (remote.isFtp() && android.os.SystemClock.elapsedRealtime() - started >= 20_000) break;
                    }
                }
            }
            remote.finishStream();
            long retained = remote.size(temporary);
            if (retained != offset) throw new java.io.EOFException("Server retained " + retained + " of " + offset + " upload bytes");
        }
    }

    private static final class ChecksumException extends IOException {
        ChecksumException() { super("Destination checksum mismatch. Local video retained for a fresh upload"); }
    }

    private static void verifyResumable(Context context, TransferConnection remote, String name, Digest expected,
            String key, Progress progress) throws IOException {
        if (remote.size(name) != expected.bytes) throw new IOException("Destination size mismatch. Local file retained");
        String fingerprint = remote.fingerprint(name);
        TransferCheckpoint checkpoint = new TransferCheckpoint(context, key, fingerprint, expected.bytes);
        progress.update(checkpoint.offset > 0 ? "Resuming verification" : "Verifying server copy", checkpoint.offset, expected.bytes);
        long savedAt = checkpoint.offset;
        while (checkpoint.offset < expected.bytes) {
            long started = android.os.SystemClock.elapsedRealtime();
            try (InputStream input = remote.read(name, checkpoint.offset)) {
                byte[] buffer = new byte[65536];
                while (checkpoint.offset < expected.bytes) {
                    remote.check();
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, expected.bytes - checkpoint.offset));
                    if (read < 0) throw new java.io.EOFException("Server copy ended before its expected size");
                    checkpoint.digest.update(buffer, 0, read);
                    checkpoint.offset += read;
                    if (checkpoint.offset - savedAt >= 4 * 1048576L) {
                        checkpoint.save(); savedAt = checkpoint.offset;
                    }
                    progress.update("Verifying server copy", checkpoint.offset, expected.bytes);
                    if (remote.isFtp() && android.os.SystemClock.elapsedRealtime() - started >= 20_000) break;
                }
            } catch (IOException error) { checkpoint.save(); throw error; }
            if (checkpoint.offset < expected.bytes && remote.isFtp()) remote.reconnectFtp();
            else remote.finishStream();
            if (!fingerprint.isEmpty() && !fingerprint.equals(remote.fingerprint(name))) {
                checkpoint.clear();
                throw new IOException("Server copy changed during verification. Local file retained");
            }
            checkpoint.save(); savedAt = checkpoint.offset;
        }
        if (!expected.sha256.equals(checkpoint.checksum())) { checkpoint.clear(); throw new ChecksumException(); }
    }

    static String remoteName(Context context, RecordingStore.Entry entry, String sha256) throws IOException {
        String install = TransferSettings.preferences(context).getString("installation", "");
        if (install.isEmpty()) {
            install = UUID.randomUUID().toString().replace("-", "");
            if (!TransferSettings.preferences(context).edit().putString("installation", install).commit())
                throw new IOException("Could not retain transfer identity");
        }
        String base = entry.name.replaceAll("[^A-Za-z0-9._-]", "_");
        int dot = base.lastIndexOf('.');
        String extension = dot > 0 ? base.substring(dot) : "";
        if (dot > 0) base = base.substring(0, dot);
        if (base.length() > 100) base = base.substring(0, 100);
        String identity = hex(hash().digest(entry.transferIdentity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return base + "-" + install.substring(0, 8) + "-" + identity.substring(0, 8)
                + "-" + sha256.substring(0, 12) + extension;
    }

    private static InputStream source(Context context, Uri uri) throws IOException {
        InputStream stream = context.getContentResolver().openInputStream(uri);
        if (stream == null) throw new IOException("Cannot open local recording");
        return stream;
    }

    private static void verify(TransferConnection remote, String name, Digest expected) throws IOException {
        if (remote.size(name) != expected.bytes) throw new IOException("Destination size mismatch. Local file retained");
        Digest actual;
        try (InputStream input = remote.read(name)) { actual = digest(input, remote); }
        remote.finishStream();
        if (!expected.equals(actual)) throw new IOException("Destination checksum mismatch. Local file retained. Expected "
                + expected.bytes + " bytes, SHA-256 " + expected.sha256 + "; received " + actual.bytes
                + " bytes, SHA-256 " + actual.sha256);
    }

    static void test(Context context, TransferSettings.Config config, BooleanSupplier cancelled) throws IOException {
        byte[] payload = ("ScreenOhRecord connection test " + UUID.randomUUID())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String temporary = "ScreenOhRecord-test-" + UUID.randomUUID() + ".uploading";
        String finalName = temporary + ".verified";
        try (TransferConnection remote = new TransferConnection(context, config, cancelled)) {
            try {
                try (OutputStream output = remote.write(temporary)) { output.write(payload); }
                remote.finishStream();
                Digest expected = digest(new ByteArrayInputStream(payload), remote);
                verify(remote, temporary, expected);
                remote.rename(temporary, finalName);
                verify(remote, finalName, expected);
                remote.remove(finalName);
                if (remote.exists(finalName)) throw new IOException("Test file cleanup failed");
            } finally {
                if (!cancelled.getAsBoolean()) {
                    try {
                        if (remote.exists(temporary)) remote.remove(temporary);
                        if (remote.exists(finalName)) remote.remove(finalName);
                    } catch (IOException error) {
                        android.util.Log.w("ScreenOhRecord", "Connection-test temporary file cleanup failed", error);
                    }
                }
            }
        }
    }

    private static final class Digest {
        final String sha256;
        final long bytes;
        Digest(String sha256, long bytes) { this.sha256 = sha256; this.bytes = bytes; }
        @Override public boolean equals(Object other) {
            return other instanceof Digest && bytes == ((Digest) other).bytes && sha256.equals(((Digest) other).sha256);
        }
        @Override public int hashCode() { return java.util.Objects.hash(sha256, bytes); }
    }

    private static Digest digest(InputStream input, TransferConnection remote) throws IOException {
        MessageDigest digest = hash();
        byte[] buffer = new byte[65536];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            remote.check();
            digest.update(buffer, 0, read);
            count += read;
        }
        return new Digest(hex(digest.digest()), count);
    }

    private static MessageDigest hash() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
