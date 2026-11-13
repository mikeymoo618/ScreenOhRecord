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

    static boolean eligible(RecordingStore.Entry entry, String destination, long now, int minutes) {
        return entry.completedAt > 0 && now >= entry.completedAt
                && now - entry.completedAt >= minutes * 60_000L && destination.equals(entry.destination);
    }

    static int run(Context context, BooleanSupplier cancelled) throws IOException {
        TransferSettings.Config config = TransferSettings.load(context);
        if (!config.enabled) return 0;
        if (RecordingService.status().active() || CaptureActivity.isRequestActive())
            throw new IOException("Transfer paused while recording or requesting screen access");
        long deadline = android.os.SystemClock.elapsedRealtime() + 8 * 60_000L;
        String destination = RecordingOptions.get(context).getString("folder_uri", "");
        BooleanSupplier stopped = () -> cancelled.getAsBoolean()
                || android.os.SystemClock.elapsedRealtime() >= deadline
                || !TransferSettings.preferences(context).getBoolean("enabled", false)
                || !config.revision.equals(TransferSettings.preferences(context).getString("revision", ""))
                || !destination.equals(RecordingOptions.get(context).getString("folder_uri", ""));
        RecordingStore store = new RecordingStore(context);
        int moved = 0;
        // Open no network connection when there are no completed, old-enough recordings.
        TransferConnection remote = null;
        try {
            for (RecordingStore.Entry entry : store.transferEntries()) {
                if (stopped.getAsBoolean()) throw new IOException("Transfer paused. Local recordings retained");
                if (!eligible(entry, destination, System.currentTimeMillis(), config.delayMinutes)) continue;
                RecordingStore.Availability availability = store.availability(entry);
                if (availability == RecordingStore.Availability.MISSING) { store.delete(entry); continue; }
                if (availability != RecordingStore.Availability.PRESENT)
                    throw new IOException("Restore access to the recording folder to resume transfers");
                if (remote == null) remote = new TransferConnection(context, config, stopped);
                move(context, store, entry, remote);
                moved++;
                status(context, "Moved " + moved + " recording(s). Local copies removed after verification.");
            }
        } finally { if (remote != null) remote.close(); }
        if (moved == 0) status(context, "Checked recordings. Waiting for the selected delay and Wi-Fi.");
        return moved;
    }

    static void status(Context context, String message) {
        TransferSettings.preferences(context).edit().putString("status", message)
                .putLong("checked_at", System.currentTimeMillis()).apply();
    }

    private static void move(Context context, RecordingStore store, RecordingStore.Entry entry,
            TransferConnection remote) throws IOException {
        Digest local;
        try (InputStream input = source(context, entry.uri)) { local = digest(input, remote); }
        if (local.bytes <= 0) throw new IOException("Empty recording retained locally");
        String name = remoteName(context, entry, local.sha256);
        String temporary = name + ".uploading";
        if (!remote.exists(name)) {
            try (InputStream input = source(context, entry.uri); OutputStream output = remote.write(temporary)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    remote.check();
                    output.write(buffer, 0, read);
                }
            }
            remote.finishStream();
            verify(remote, temporary, local);
            remote.rename(temporary, name);
        }
        // Also verifies a final file left by a previous run that could not remove the local copy.
        verify(remote, name, local);
        Digest current;
        try (InputStream input = source(context, entry.uri)) { current = digest(input, remote); }
        if (!local.equals(current)) throw new IOException("Local recording changed during transfer. Local file retained");
        remote.check();
        try { store.delete(entry); }
        catch (RuntimeException error) { throw new IOException("Remote copy verified. Restore folder access to remove the local copy", error); }
    }

    static String remoteName(Context context, RecordingStore.Entry entry, String sha256) throws IOException {
        String install = TransferSettings.preferences(context).getString("installation", "");
        if (install.isEmpty()) {
            install = UUID.randomUUID().toString().replace("-", "");
            if (!TransferSettings.preferences(context).edit().putString("installation", install).commit())
                throw new IOException("Could not retain transfer identity");
        }
        String base = entry.name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.endsWith(".mp4")) base = base.substring(0, base.length() - 4);
        if (base.length() > 100) base = base.substring(0, 100);
        String identity = hex(hash().digest(entry.uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return base + "-" + install.substring(0, 8) + "-" + identity.substring(0, 8)
                + "-" + sha256.substring(0, 12) + ".mp4";
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
