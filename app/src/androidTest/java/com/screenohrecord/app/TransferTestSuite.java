package com.screenohrecord.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Disposable local-server integration tests. Run only on the task-owned emulators. */
final class TransferTestSuite {
    private final Instrumentation instrumentation;
    private final Context context;
    private final RecordingStore store;
    private final List<RecordingStore.Entry> fixtures = new ArrayList<>();
    private boolean ownsGate;
    private final String host;
    private static final String PASSWORD = "local-fixture-only";
    private static final byte[] CONTENT = new byte[262144];

    TransferTestSuite(Instrumentation instrumentation, String host) {
        this.instrumentation = instrumentation;
        this.host = host;
        context = instrumentation.getTargetContext();
        store = new RecordingStore(context);
        for (int i = 0; i < CONTENT.length; i++) CONTENT[i] = (byte) (i * 31);
    }

    void run() throws Exception {
        SharedPreferences options = RecordingOptions.get(context);
        SharedPreferences records = context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE);
        SharedPreferences transfers = TransferSettings.preferences(context);
        Map<String, ?> oldOptions = new HashMap<>(options.getAll());
        Map<String, ?> oldRecords = new HashMap<>(records.getAll());
        Map<String, ?> oldTransfers = new HashMap<>(transfers.getAll());
        try {
            options.edit().remove("folder_uri").remove("folder_label").commit();
            records.edit().clear().putString("transfer_scanned", "").commit();
            transfers.edit().clear().commit();
            check(!transfers.getBoolean("enabled", false), "Transfers enabled by default");
            shell("svc wifi enable");
            long wifiDeadline = SystemClock.elapsedRealtime() + 45000;
            while (TransferConnection.wifi(context) == null && SystemClock.elapsedRealtime() < wifiDeadline) SystemClock.sleep(200);
            android.net.ConnectivityManager manager = context.getSystemService(android.net.ConnectivityManager.class);
            check(TransferConnection.wifi(context) != null, "Emulator Wi-Fi missing: "
                    + manager.getNetworkCapabilities(manager.getActiveNetwork()));
            testSettings();
            acquireGate();
            testQueue();
            testDiscovery();
            testProtocol("ftp");
            testProtocol("smb");
            testCorruptionAndReadOnly();
            testFolder();
            testWifi();
            testJob();
            pass("transfer suite complete");
        } finally {
            if (ownsGate) { TransferEngine.end(); ownsGate = false; }
            shell("svc wifi enable");
            for (RecordingStore.Entry entry : fixtures) {
                try { store.delete(entry); } catch (RuntimeException ignored) { }
            }
            restore(options, oldOptions);
            restore(records, oldRecords);
            restore(transfers, oldTransfers);
            TransferScheduler.schedule(context);
        }
    }

    private TransferSettings.Config config(String protocol, boolean enabled, String folder, String username, String password, int delay) throws IOException {
        return new TransferSettings.Config(protocol, host, "ftp".equals(protocol) ? 2121 : 2445,
                "video files", folder, "", username, password, delay, enabled, "");
    }

    private TransferSettings.Config config(String protocol) throws IOException {
        return config(protocol, true, "videos", "screenohrecord-test", PASSWORD, 1);
    }

    private void testSettings() throws Exception {
        TransferSettings.save(context, config("ftp"));
        TransferSettings.Config loaded = TransferSettings.load(context);
        check(PASSWORD.equals(loaded.password) && loaded.port == 2121 && loaded.delayMinutes == 1, "Settings round-trip failed");
        check(!TransferSettings.preferences(context).getString("password", "").contains(PASSWORD), "Password stored in plaintext");
        JobInfo job = context.getSystemService(JobScheduler.class).getPendingJob(TransferScheduler.JOB_ID);
        check(job != null && job.isPersisted() && job.getIntervalMillis() == 900000, "Transfer scheduling incorrect");
        TransferSettings.save(context, config("ftp", false, "videos", "screenohrecord-test", PASSWORD, 60));
        check(context.getSystemService(JobScheduler.class).getPendingJob(TransferScheduler.JOB_ID) == null, "Disabled job retained");
        acquireGate();
        check(!TransferEngine.begin(), "Parallel transfer exclusion failed");
        TransferEngine.end(); ownsGate = false;
        Activity screen = instrumentation.startActivitySync(new Intent(context, TransfersActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            check(screen.findViewById(R.id.transfer_test) != null && screen.findViewById(R.id.transfer_save) != null,
                    "Transfer controls absent");
            screen.finish();
        });
        pass("transfer defaults, encrypted password round-trip, scheduling, disable, concurrency and settings UI");
    }

    private void testQueue() throws Exception {
        RecordingStore.Entry first = fixture(new byte[]{1, 2, 3});
        context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE).edit().remove("history").commit();
        check(store.transferEntries().stream().anyMatch(entry -> entry.uri.equals(first.uri)), "Queue depends on recent history");
        check(!TransferEngine.eligible(first, "", System.currentTimeMillis(), 60), "Young recording eligible");
        check(!TransferEngine.eligible(first, "other-folder", System.currentTimeMillis() + 3_600_000, 1), "Other folder eligible");
        check(!TransferEngine.eligible(first, "", first.completedAt - 1, 1), "Future recording eligible");
        TransferSettings.save(context, config("ftp", false, "videos", "screenohrecord-test", PASSWORD, 1));
        check(TransferEngine.run(context, () -> false) == 0 && present(first), "Disabled feature moved a recording");
        TransferSettings.save(context, config("ftp"));
        java.lang.reflect.Field requesting = CaptureActivity.class.getDeclaredField("requestActive");
        requesting.setAccessible(true);
        boolean wasRequesting = requesting.getBoolean(null);
        try {
            requesting.setBoolean(null, true);
            expectFailure(() -> TransferEngine.run(context, () -> false));
            check(present(first), "Capture-consent exclusion removed local file");
        } finally { requesting.setBoolean(null, wasRequesting); }
        store.delete(first);
        check(store.transferEntries().isEmpty(), "Deleted video remains queued");
        pass("durable completion queue, delay, clock rollback, current-folder scope, capture-consent exclusion and disabled-file preservation");
    }

    private void testProtocol(String protocol) throws Exception {
        TransferSettings.Config config = config(protocol);
        TransferEngine.test(context, config, () -> false);
        pass(protocol + " connection upload/read-back/checksum/rename/delete");
        RecordingStore.Entry video = fixture(CONTENT);
        age(video);
        TransferSettings.save(context, config(protocol, true, "videos", "screenohrecord-test", "wrong-fixture-password", 1));
        expectFailure(() -> TransferEngine.run(context, () -> false));
        check(present(video), "Login failure removed local file");
        TransferSettings.save(context, config);
        try {
            check(TransferEngine.run(context, () -> false) == 1 && !present(video), "Verified move failed");
        } catch (IOException failure) {
            diagnoseRead(config, video);
            throw failure;
        }
        check(store.transferEntries().isEmpty(), "Transferred item remains queued");
        pass(protocol + " wrong credentials retain local recording, verified move and history/queue removal");

        RecordingStore.Entry cancelled = fixture(CONTENT);
        age(cancelled);
        AtomicInteger checks = new AtomicInteger();
        expectFailure(() -> TransferEngine.run(context, () -> checks.incrementAndGet() > 10));
        check(present(cancelled), "Cancellation removed local file");
        check(TransferEngine.run(context, () -> false) == 1 && !present(cancelled), "Cancelled transfer did not recover");
        pass(protocol + " interrupted transfer preserves local video and retry succeeds");

        RecordingStore.Entry conflict = fixture(CONTENT);
        age(conflict);
        String name = TransferEngine.remoteName(context, conflict, hash(CONTENT));
        try (TransferConnection remote = new TransferConnection(context, config, () -> false)) {
            try (OutputStream output = remote.write(name)) { output.write(new byte[]{9, 8, 7}); }
            remote.finishStream();
        }
        expectFailure(() -> TransferEngine.run(context, () -> false));
        check(present(conflict), "Conflicting remote file removed local video");
        try (TransferConnection remote = new TransferConnection(context, config, () -> false)) {
            check(remote.size(name) == 3, "Conflicting remote file overwritten");
            remote.remove(name);
            try (OutputStream output = remote.write(name)) { output.write(CONTENT); }
            remote.finishStream();
        }
        check(TransferEngine.run(context, () -> false) == 1 && !present(conflict), "Existing verified destination recovery failed");
        pass(protocol + " collision preserves both files and a previously completed remote copy is verified/reused");
    }

    private void diagnoseRead(TransferSettings.Config config, RecordingStore.Entry entry) throws Exception {
        if (!"ftp".equals(config.protocol)) return;
        String name = TransferEngine.remoteName(context, entry, hash(CONTENT));
        try (TransferConnection remote = new TransferConnection(context, config, () -> false)) {
            if (!remote.exists(name)) name += ".uploading";
            java.lang.reflect.Field client = TransferConnection.class.getDeclaredField("ftp");
            client.setAccessible(true);
            Object ftp = client.get(remote);
            java.lang.reflect.Field type = ftp.getClass().getDeclaredField("fileType");
            type.setAccessible(true);
            Bundle state = new Bundle(); state.putString("stream", "INFO: FTP binary type=" + type.getInt(ftp) + "\n");
            instrumentation.sendStatus(0, state);
            for (int attempt = 0; attempt < 3; attempt++) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream input = remote.read(name)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) { remote.check(); bytes.write(buffer, 0, count); }
                }
                remote.finishStream();
                byte[] actual = bytes.toByteArray();
                int mismatch = 0;
                while (mismatch < Math.min(actual.length, CONTENT.length) && actual[mismatch] == CONTENT[mismatch]) mismatch++;
                Bundle report = new Bundle(); report.putString("stream", "INFO: FTP diagnostic read " + attempt + " received="
                        + actual.length + " first mismatch=" + mismatch + " sha256=" + hash(actual) + "\n");
                instrumentation.sendStatus(0, report);
                java.io.File file = new java.io.File(context.getExternalFilesDir(null), "transfer-diagnostic-" + attempt + ".bin");
                try (OutputStream output = new java.io.FileOutputStream(file)) { output.write(actual); }
            }
        }
    }

    private void testDiscovery() throws Exception {
        byte[] source = java.nio.file.Files.readAllBytes(new java.io.File(context.getExternalFilesDir(null), "transfer-source.mp4").toPath());
        RecordingStore.Entry video = fixture(source);
        String path;
        try (android.database.Cursor cursor = context.getContentResolver().query(video.uri,
                new String[]{android.provider.MediaStore.Video.Media.DATA}, null, null, null)) {
            check(cursor != null && cursor.moveToFirst(), "Synthetic source path unavailable");
            path = cursor.getString(0);
        }
        java.util.concurrent.CountDownLatch scanned = new java.util.concurrent.CountDownLatch(1);
        android.media.MediaScannerConnection.scanFile(context, new String[]{path}, new String[]{"video/mp4"},
                (ignored, uri) -> scanned.countDown());
        check(scanned.await(20, java.util.concurrent.TimeUnit.SECONDS), "Synthetic MP4 scan timed out");
        SharedPreferences p = context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE);
        p.edit().remove("transfer_pending").remove("history").remove("transfer_scanned").commit();
        check(store.transferEntries().stream().anyMatch(entry -> entry.uri.equals(video.uri)),
                "Directory scan missed completed video absent from recent history");
        store.delete(video);
        p.edit().clear().putBoolean("transfer_migrated", true).putString("transfer_scanned", "").commit();
        RecordingStore.Output unfinished = store.create();
        try {
            check(store.transferEntries().isEmpty(), "Unfinished recording queued");
        } finally { unfinished.descriptor.close(); store.discard(unfinished.uri); }
        List<RecordingStore.Entry> batch = new ArrayList<>();
        for (int i = 0; i < 51; i++) batch.add(fixture(new byte[]{1, 2, 3}));
        check(store.entries().size() == 50 && store.transferEntries().size() == 51, "Queue lost recordings beyond recent-history limit");
        for (RecordingStore.Entry entry : batch) store.delete(entry);
        pass("directory scan discovers completed files outside history, unfinished exclusion and queue beyond 50 recordings");
    }

    private void testCorruptionAndReadOnly() throws Exception {
        RecordingStore.Entry video = fixture(CONTENT);
        age(video);
        TransferSettings.save(context, config("ftp", true, "corrupt", "screenohrecord-test", PASSWORD, 1));
        expectFailure(() -> TransferEngine.run(context, () -> false));
        check(present(video), "Corrupt remote file removed local video");
        TransferSettings.save(context, config("ftp", true, "videos", "readonly-test", PASSWORD, 1));
        expectFailure(() -> TransferEngine.run(context, () -> false));
        check(present(video), "Read-only destination removed local video");
        TransferSettings.save(context, config("ftp", true, "missing-folder", "screenohrecord-test", PASSWORD, 1));
        expectFailure(() -> TransferEngine.run(context, () -> false));
        check(present(video), "Missing destination removed local video");
        TransferSettings.save(context, config("ftp"));
        check(TransferEngine.run(context, () -> false) == 1, "Failed transfer recovery did not move video");
        pass("checksum corruption, read-only destination and missing folder preserve local file, then recover");
    }

    private void testFolder() throws Exception {
        Uri tree = null;
        for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && permission.isWritePermission()
                    && SettingsActivity.isLocalTree(permission.getUri())) { tree = permission.getUri(); break; }
        }
        if (tree == null) {
            shell("mkdir -p /sdcard/Movies/SRT");
            Activity settings = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.runOnMainSync(() -> settings.findViewById(R.id.settings_folder).performClick());
            PrivacyTestRunner runner = (PrivacyTestRunner) instrumentation;
            runner.clickSystemText("SRT");
            if (android.os.Build.VERSION.SDK_INT == 28) runner.clickSystemText("Select");
            else { runner.clickSystemText("Use this folder"); runner.clickSystemText("Allow"); }
            long deadline = SystemClock.elapsedRealtime() + 8000;
            while (!RecordingOptions.get(context).contains("folder_uri") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            check(RecordingOptions.get(context).contains("folder_uri"), "Folder picker did not retain transfer source");
            tree = Uri.parse(RecordingOptions.get(context).getString("folder_uri", ""));
            instrumentation.runOnMainSync(settings::finish);
        }
        RecordingOptions.get(context).edit().putString("folder_uri", tree.toString()).commit();
        context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE).edit().putString("transfer_scanned", tree.toString()).commit();
        RecordingStore.Output output = store.create();
        try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output.descriptor)) { stream.write(CONTENT); }
        RecordingOptions.get(context).edit().remove("folder_uri").commit();
        store.complete(output, CONTENT.length, 1000);
        context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE).edit().putString("transfer_scanned", "").commit();
        RecordingStore.Entry entry = null;
        for (RecordingStore.Entry candidate : store.transferEntries()) {
            if (candidate.uri.equals(output.uri)) { entry = candidate; fixtures.add(candidate); break; }
        }
        check(entry != null && tree.toString().equals(entry.destination), "Changing settings during recording changed the saved file's folder identity");
        RecordingOptions.get(context).edit().putString("folder_uri", tree.toString()).commit();
        context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE).edit().putString("transfer_scanned", tree.toString()).commit();
        age(entry);
        TransferSettings.save(context, config("smb"));
        check(TransferEngine.run(context, () -> false) == 1 && !present(entry), "Selected folder SMB move failed");
        RecordingOptions.get(context).edit().remove("folder_uri").commit();
        context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE).edit().putString("transfer_scanned", "").commit();
        pass("selected local document-folder recording transfers and deletes through persisted URI grant");
    }

    private void testWifi() throws Exception {
        RecordingStore.Entry entry = fixture(CONTENT);
        age(entry);
        TransferSettings.save(context, config("ftp"));
        TransferConnection ftp = new TransferConnection(context, config("ftp"), () -> false);
        TransferConnection smb = new TransferConnection(context, config("smb"), () -> false);
        shell("svc wifi disable");
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (TransferConnection.wifi(context) != null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(TransferConnection.wifi(context) == null, "Wi-Fi did not disable");
        try {
            expectFailure(() -> ftp.exists("wifi-loss-test"));
            expectFailure(() -> smb.exists("wifi-loss-test"));
            expectFailure(() -> TransferEngine.run(context, () -> false));
            check(present(entry), "Wi-Fi loss removed local video");
        } finally { ftp.close(); smb.close(); shell("svc wifi enable"); }
        deadline = SystemClock.elapsedRealtime() + 30000;
        while (TransferConnection.wifi(context) == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
        check(TransferConnection.wifi(context) != null, "Wi-Fi did not recover");
        check(TransferEngine.run(context, () -> false) == 1, "Wi-Fi recovery failed");
        pass("Wi-Fi off blocks transfer and retains local recording, Wi-Fi recovery completes move");
    }

    private void testJob() throws Exception {
        RecordingStore.Entry video = fixture(CONTENT);
        age(video);
        TransferSettings.save(context, config("ftp"));
        TransferEngine.end(); ownsGate = false;
        shell("cmd jobscheduler run -f com.screenohrecord.app " + TransferScheduler.JOB_ID);
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (present(video) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
        check(!present(video), "Scheduled background job did not move recording");
        acquireGate();
        pass("actual Android background job transfers an eligible video over Wi-Fi");
    }

    private void acquireGate() {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!ownsGate && SystemClock.elapsedRealtime() < deadline) {
            ownsGate = TransferEngine.begin();
            if (!ownsGate) SystemClock.sleep(100);
        }
        check(ownsGate, "Could not acquire transfer exclusion gate");
    }

    private RecordingStore.Entry fixture(byte[] content) throws Exception {
        RecordingStore.Output output = store.create();
        try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output.descriptor)) { stream.write(content); }
        store.complete(output, content.length, 1000);
        for (RecordingStore.Entry entry : store.transferEntries()) {
            if (output.uri.equals(entry.uri)) { fixtures.add(entry); return entry; }
        }
        throw new AssertionError("Completed fixture missing from queue");
    }

    private void age(RecordingStore.Entry entry) throws Exception {
        SharedPreferences p = context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE);
        JSONArray queue = new JSONArray(p.getString("transfer_pending", "[]"));
        for (int i = 0; i < queue.length(); i++) {
            JSONObject item = queue.getJSONObject(i);
            if (entry.uri.toString().equals(item.getString("uri"))) item.put("completed_at", System.currentTimeMillis() - 120000);
        }
        check(p.edit().putString("transfer_pending", queue.toString()).commit(), "Could not age fixture");
    }

    private boolean present(RecordingStore.Entry entry) { return store.availability(entry) == RecordingStore.Availability.PRESENT; }
    private interface Operation { void run() throws Exception; }
    private void expectFailure(Operation operation) throws Exception {
        try { operation.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected transfer failure");
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void pass(String message) {
        Bundle bundle = new Bundle(); bundle.putString("stream", "PASS: " + message + "\n"); instrumentation.sendStatus(0, bundle);
    }
    private void shell(String command) throws IOException {
        try (ParcelFileDescriptor result = instrumentation.getUiAutomation().executeShellCommand(command);
                java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            byte[] buffer = new byte[4096];
            while (input.read(buffer) != -1) { }
        }
    }
    private String hash(byte[] content) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(content)) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    @SuppressWarnings("unchecked")
    private void restore(SharedPreferences p, Map<String, ?> values) {
        SharedPreferences.Editor editor = p.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof java.util.Set) editor.putStringSet(entry.getKey(), (java.util.Set<String>) value);
        }
        check(editor.commit(), "Could not restore emulator settings");
    }
}
