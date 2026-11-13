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
            chooseTestFolder();
            testSettings();
            acquireGate();
            testQueue();
            testDiscovery();
            testBroadDiscovery();
            testFolderScope();
            testDefaultFolderAliases();
            testFailedFileContinuation();
            testProtocol("ftp");
            testProtocol("smb");
            testCorruptionAndReadOnly();
            testFolder();
            testWifi();
            testConcurrentRecording();
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
        check(!TransferEngine.eligible(first, first.destination, System.currentTimeMillis(), 60), "Young recording eligible");
        check(!TransferEngine.eligible(first, "other-folder", System.currentTimeMillis() + 3_600_000, 1), "Other folder eligible");
        check(!TransferEngine.eligible(first, first.destination, first.completedAt - 1, 1), "Future recording eligible");
        TransferSettings.save(context, config("ftp", false, "videos", "screenohrecord-test", PASSWORD, 1));
        check(TransferEngine.run(context, () -> false) == 0 && present(first), "Disabled feature moved a recording");
        TransferSettings.save(context, config("ftp"));
        java.lang.reflect.Field requesting = CaptureActivity.class.getDeclaredField("requestActive");
        requesting.setAccessible(true);
        boolean wasRequesting = requesting.getBoolean(null);
        try {
            requesting.setBoolean(null, true);
            check(TransferEngine.run(context, () -> false) == 0, "Young video moved during capture consent");
            TransferEngine.test(context, config("ftp"), () -> false);
            check(present(first), "Connection test removed local file during capture consent");
        } finally { requesting.setBoolean(null, wasRequesting); }
        store.delete(first);
        check(store.transferEntries().isEmpty(), "Deleted video remains queued");
        pass("durable completion queue, delay, clock rollback, current-folder scope, connection test during capture consent and disabled-file preservation");
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

    private void chooseTestFolder() throws Exception {
        String name = "VT" + Long.toHexString(System.nanoTime() & 0xffffff);
        chooseTestFolder(name);
    }

    private void chooseTestFolder(String name) throws Exception {
        shell("mkdir -p /sdcard/Movies/" + name);
        Activity settings = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            picker.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                            "primary:Movies/" + name));
            settings.startActivityForResult(picker, 20);
        });
        PrivacyTestRunner runner = (PrivacyTestRunner) instrumentation;
        if (android.os.Build.VERSION.SDK_INT == 28) runner.clickSystemText("Select");
        else { runner.clickSystemText("Use this folder"); runner.clickSystemText("Allow"); }
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (!RecordingOptions.get(context).getString("folder_uri", "").contains(name)
                && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(RecordingOptions.get(context).getString("folder_uri", "").contains(name), "Test folder grant missing");
        instrumentation.runOnMainSync(settings::finish);
    }

    private void testDiscovery() throws Exception {
        RecordingStore.Entry video = fixture(CONTENT);
        SharedPreferences p = context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE);
        p.edit().remove("transfer_pending").remove("history").commit();
        store.scanTransferFolder();
        check(store.transferEntries().stream().anyMatch(entry -> entry.uri.equals(video.uri)),
                "Directory scan missed video absent from recent history");
        store.delete(video);
        RecordingStore.Output unfinished = store.create();
        try {
            store.scanTransferFolder();
            check(store.transferEntries().isEmpty(), "Unfinished recording queued");
        } finally { unfinished.descriptor.close(); store.discard(unfinished.uri); }
        List<RecordingStore.Entry> batch = new ArrayList<>();
        for (int i = 0; i < 51; i++) batch.add(fixture(new byte[]{1, 2, 3}));
        check(store.entries().size() == 50 && store.transferEntries().size() == 51, "Queue lost videos beyond recent-history limit");
        for (RecordingStore.Entry entry : batch) store.delete(entry);
        pass("recurring directory scan discovers videos outside history, excludes unfinished output and retains more than 50 files");
    }

    private RecordingStore.Entry externalVideo(String name, String mime, byte[] content) throws Exception {
        Uri tree = Uri.parse(RecordingOptions.get(context).getString("folder_uri", ""));
        Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                android.provider.DocumentsContract.getTreeDocumentId(tree));
        Uri uri = android.provider.DocumentsContract.createDocument(context.getContentResolver(), parent, mime, name);
        check(uri != null, "Could not create folder-discovery fixture");
        try (OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) { output.write(content); }
        store.scanTransferFolder();
        for (RecordingStore.Entry entry : store.transferEntries()) {
            if (entry.uri.equals(uri)) { fixtures.add(entry); return entry; }
        }
        throw new AssertionError("Arbitrary video file was not discovered: " + name);
    }

    private void testBroadDiscovery() throws Exception {
        check(RecordingStore.videoFile("older filename.MKV", "application/octet-stream"), "MKV extension rejected");
        check(RecordingStore.videoFile("camera export", "video/webm"), "Video MIME type rejected");
        check(!RecordingStore.videoFile("notes.txt", "text/plain"), "Non-video selected");
        check(!RecordingStore.videoFile("folder.mp4", android.provider.DocumentsContract.Document.MIME_TYPE_DIR), "Directory selected as video");
        RecordingStore.Entry first = externalVideo("old app recording.MKV", "video/x-matroska", CONTENT);
        age(first);
        TransferSettings.save(context, config("ftp"));
        check(TransferEngine.run(context, () -> false) == 1 && !present(first), "Arbitrary old MKV did not move");
        check(TransferEngine.remoteName(context, first, hash(CONTENT))
                .endsWith(first.name.substring(first.name.lastIndexOf('.'))), "Original extension changed");
        RecordingStore.Entry later = externalVideo("second recorder clip.webm", "video/webm", CONTENT);
        age(later);
        check(TransferEngine.run(context, () -> false) == 1 && !present(later), "New file after initial scan was missed");
        RecordingStore.Entry edited = externalVideo("edited video.mov", "video/quicktime", CONTENT);
        age(edited);
        touch(edited.uri, false);
        check(TransferEngine.run(context, () -> false) == 0 && present(edited), "Recently modified file used an old queue timestamp");
        age(edited);
        check(TransferEngine.run(context, () -> false) == 1 && !present(edited), "Aged modified video did not move");
        pass("any filenames and video formats, recurring discovery, original extensions and refreshed modification timestamps");
    }

    private void testFolderScope() throws Exception {
        Uri tree = Uri.parse(RecordingOptions.get(context).getString("folder_uri", ""));
        Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                android.provider.DocumentsContract.getTreeDocumentId(tree));
        Uri text = android.provider.DocumentsContract.createDocument(context.getContentResolver(), parent,
                "text/plain", "keep these notes.txt");
        check(text != null, "Could not create non-video fixture");
        String outside = "/sdcard/Movies/outside-" + Long.toHexString(System.nanoTime()) + ".mkv";
        try {
            try (OutputStream output = context.getContentResolver().openOutputStream(text, "w")) {
                output.write(CONTENT);
            }
            touch(text, true);
            RecordingStore.Entry moved = externalVideo("moved outside.mkv", "video/x-matroska", CONTENT);
            age(moved);
            String id = android.provider.DocumentsContract.getDocumentId(moved.uri);
            String path = android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/" + id.substring("primary:".length());
            shell("mv '" + path.replace("'", "'\\''") + "' '" + outside + "'");
            store.scanTransferFolder();
            check(store.transferEntries().stream().noneMatch(entry -> entry.uri.equals(moved.uri)
                    || entry.uri.equals(text)), "Out-of-folder or non-video file remained queued");
            TransferSettings.save(context, config("ftp"));
            check(TransferEngine.run(context, () -> false) == 0, "Folder-scope exclusions transferred");
            try (java.io.InputStream input = context.getContentResolver().openInputStream(text)) {
                check(input != null && input.read() == (CONTENT[0] & 255), "Non-video file changed");
            }
            check(RecordingStore.sameDirectory("", android.provider.DocumentsContract.buildTreeDocumentUri(
                    SettingsActivity.LOCAL_AUTHORITY, "primary:Movies/ScreenOhRecord").toString()),
                    "Default-folder alias changed identity");
        } finally {
            android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), text);
            shell("rm -f '" + outside + "'");
        }
        pass("old non-video remains untouched, moved-out video leaves queue and default folder aliases match");
    }

    private void testDefaultFolderAliases() throws Exception {
        SharedPreferences options = RecordingOptions.get(context);
        SharedPreferences records = context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE);
        Map<String, ?> previousRecords = new HashMap<>(records.getAll());
        String selected = options.getString("folder_uri", "");
        String label = options.getString("folder_label", "");
        RecordingStore.Output output = null;
        java.io.File raw = null;
        try {
            if (android.os.Build.VERSION.SDK_INT == 28) {
                shell("pm grant " + context.getPackageName() + " android.permission.READ_EXTERNAL_STORAGE");
                shell("pm grant " + context.getPackageName() + " android.permission.WRITE_EXTERNAL_STORAGE");
            }
            options.edit().remove("folder_uri").remove("folder_label").commit();
            output = store.create();
            try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output.descriptor)) {
                stream.write(CONTENT);
            }
            Uri media = output.uri;
            chooseTestFolder("ScreenOhRecord");
            store.scanTransferFolder();
            String name = output.name;
            check(store.transferEntries().stream().noneMatch(entry -> entry.name.equals(name)),
                    "Active MediaStore output queued through its document alias");
            store.complete(output, CONTENT.length, 0);
            store.scanTransferFolder();
            List<RecordingStore.Entry> aliases = new ArrayList<>();
            for (RecordingStore.Entry entry : store.transferEntries()) {
                if (entry.name.equals(name)) aliases.add(entry);
            }
            check(aliases.size() == 1 && aliases.get(0).transferIdentity.equals(media.toString()),
                    "MediaStore-to-document migration duplicated or changed transfer identity");
            RecordingStore.Entry rebound = aliases.get(0);
            check(store.entries().stream().anyMatch(entry -> entry.uri.equals(rebound.uri)),
                    "Recent history retained the old MediaStore alias");
            store.delete(rebound);
            check(store.entries().stream().noneMatch(entry -> entry.name.equals(name)), "Alias history retained after deletion");
            if (android.os.Build.VERSION.SDK_INT == 28) {
                options.edit().remove("folder_uri").remove("folder_label").commit();
                raw = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MOVIES), "ScreenOhRecord/unindexed-" + System.nanoTime() + ".mkv");
                try (OutputStream stream = new java.io.FileOutputStream(raw)) { stream.write(CONTENT); }
                check(raw.setLastModified(1577836800000L), "Could not age raw video");
                store.scanTransferFolder();
                Uri rawUri = Uri.fromFile(raw);
                RecordingStore.Entry discovered = null;
                for (RecordingStore.Entry entry : store.transferEntries()) {
                    if (entry.uri.equals(rawUri)) discovered = entry;
                }
                check(discovered != null && discovered.completedAt == raw.lastModified(), "Unindexed default-folder video missed");
                store.delete(discovered);
                check(!raw.exists(), "Unindexed default-folder video was not permanently deleted");
            }
        } finally {
            if (output != null) store.discard(output.uri);
            if (raw != null && raw.exists()) raw.delete();
            options.edit().putString("folder_uri", selected).putString("folder_label", label).commit();
            restore(records, previousRecords);
        }
        pass("default-folder MediaStore/document alias migration, active-output exclusion, stable identity and history cleanup"
                + (android.os.Build.VERSION.SDK_INT == 28 ? "; unindexed Android 9 video discovery and permanent deletion" : ""));
    }

    private void testFailedFileContinuation() throws Exception {
        RecordingStore.Entry empty = externalVideo("broken empty video.mp4", "video/mp4", new byte[0]);
        RecordingStore.Entry healthy = externalVideo("healthy older video.avi", "video/x-msvideo", CONTENT);
        age(empty); age(healthy);
        TransferSettings.save(context, config("ftp"));
        check(TransferEngine.run(context, () -> false) == 1, "One bad file blocked the remaining batch");
        check(present(empty) && !present(healthy), "Failed-file preservation or healthy-file deletion failed");
        check(TransferSettings.preferences(context).getString("status", "").contains("1 video(s) retained"), "Partial failure status missing");
        store.delete(empty);
        pass("failed file stays local while another eligible video moves, verifies and deletes successfully");
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
        String source = RecordingOptions.get(context).getString("folder_uri", "");
        RecordingStore.Output output = store.create();
        try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output.descriptor)) { stream.write(CONTENT); }
        RecordingOptions.get(context).edit().remove("folder_uri").commit();
        store.complete(output, CONTENT.length, 1000);
        RecordingStore.Entry entry = null;
        for (RecordingStore.Entry candidate : store.transferEntries()) {
            if (candidate.uri.equals(output.uri)) { entry = candidate; fixtures.add(candidate); break; }
        }
        check(entry != null && source.equals(entry.destination), "Changing settings changed the saved file's folder identity");
        RecordingOptions.get(context).edit().putString("folder_uri", source).commit();
        age(entry);
        TransferSettings.save(context, config("smb"));
        check(TransferEngine.run(context, () -> false) == 1 && !present(entry), "Selected folder SMB move failed");
        pass("document-folder move and permanent deletion preserve actual source identity when folder settings change during recording");
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

    private void testConcurrentRecording() throws Exception {
        PrivacyTestRunner runner = (PrivacyTestRunner) instrumentation;
        SharedPreferences records = context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE);
        RecordingOptions.get(context).edit().putInt("quality", 720)
                .putInt("fps", 15).putInt("bitrate", 500000).putString("codec", "h264")
                .putBoolean("microphone", true).commit();
        records.edit().putString("transfer_scanned", "").commit();
        List<RecordingStore.Entry> completedVideos = new ArrayList<>();
        completedVideos.add(fixture(CONTENT));
        completedVideos.add(fixture(CONTENT));
        Activity main = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> CaptureActivity.launch(context, true, 720));
            runner.consent(true);
            runner.awaitState(RecordingService.State.RECORDING);
            runner.awaitRequestFinished();
            SystemClock.sleep(1500);
            String activeUri = records.getString("unfinished", null);
            check(activeUri != null, "Active recording marker missing");
            Uri active = Uri.parse(activeUri);
            check(store.isUnfinished(active), "Active recording not recognized as unfinished");
            int index = 0;
            for (String protocol : new String[]{"ftp", "smb"}) {
                RecordingStore.Entry completed = completedVideos.get(index++);
                age(completed);
                // An old queue entry must never make an unfinished output eligible.
                JSONArray queue = new JSONArray(records.getString("transfer_pending", "[]"));
                JSONObject unfinished = new JSONObject().put("uri", activeUri).put("name", "active-must-stay.mp4")
                        .put("bytes", 1).put("duration", 1).put("completed_at", System.currentTimeMillis() - 120000)
                        .put("destination", RecordingOptions.get(context).getString("folder_uri", ""));
                queue.put(unfinished);
                records.edit().putString("transfer_pending", queue.toString()).commit();
                TransferSettings.Config destination = config(protocol);
                TransferSettings.save(context, destination);
                check(TransferEngine.run(context, () -> false) == 1, "Completed video did not move while recording via " + protocol);
                check(store.availability(completed) == RecordingStore.Availability.MISSING, "Transferred local video remains");
                check(store.entries().stream().noneMatch(entry -> completed.uri.equals(entry.uri)), "Transferred video remains in history");
                check(activeUri.equals(records.getString("unfinished", null)), "Moving a saved video cleared the active recording marker");
                check(RecordingService.status().state == RecordingService.State.RECORDING, "Transfer stopped the recording");
                try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(active, "r")) {
                    check(descriptor != null, "Active recording was deleted");
                }
                String name = TransferEngine.remoteName(context, completed, hash(CONTENT));
                try (TransferConnection remote = new TransferConnection(context, destination, () -> false)) {
                    check(remote.exists(name) && remote.size(name) == CONTENT.length, "Final remote copy missing");
                    check(!remote.exists(name + ".uploading"), "Successful move left a partial upload");
                }
                check(store.transferEntries().stream().noneMatch(entry -> completed.uri.equals(entry.uri)), "Completed transfer remains queued");
                JSONArray remaining = new JSONArray();
                JSONArray after = new JSONArray(records.getString("transfer_pending", "[]"));
                for (int row = 0; row < after.length(); row++) {
                    JSONObject item = after.getJSONObject(row);
                    if (!activeUri.equals(item.getString("uri"))) remaining.put(item);
                }
                records.edit().putString("transfer_pending", remaining.toString()).commit();
                pass(protocol + " completed video verified/moved/deleted during live microphone recording, active output and recovery marker preserved");
            }
            runner.notificationAction("Stop");
            runner.awaitState(RecordingService.State.IDLE);
            RecordingStore.Entry saved = null;
            for (RecordingStore.Entry entry : store.transferEntries()) if (entry.uri.toString().equals(activeUri)) saved = entry;
            check(saved != null, "Concurrent recording was not finalized into history and transfer queue");
            fixtures.add(saved);
            check(!TransferEngine.eligible(saved, RecordingOptions.get(context).getString("folder_uri", ""), System.currentTimeMillis(), 1), "Newly finished recording ignored the delay");
            android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever();
            try {
                metadata.setDataSource(context, saved.uri);
                String duration = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                check(duration != null && Long.parseLong(duration) > 1000, "Concurrent recording has no duration");
                check("yes".equals(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)), "Concurrent recording lost microphone audio");
                android.graphics.Bitmap frame = metadata.getScaledFrameAtTime(0,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90);
                check(frame != null, "Concurrent recording is not decodable");
                frame.recycle();
            } finally { metadata.release(); }
            store.delete(saved);
            pass("concurrent recording finalizes as a decodable MP4 with microphone audio and starts its own transfer delay");
        } finally {
            if (RecordingService.status().active()) {
                runner.notificationAction("Stop"); runner.awaitState(RecordingService.State.IDLE);
            }
            instrumentation.runOnMainSync(main::finish);
        }
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
        touch(entry.uri, true);
    }

    private void touch(Uri uri, boolean old) throws Exception {
        String path;
        if (SettingsActivity.LOCAL_AUTHORITY.equals(uri.getAuthority())) {
            String id = android.provider.DocumentsContract.getDocumentId(uri);
            check(id.startsWith("primary:"), "Fixture must be in the primary test folder");
            path = android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + id.substring("primary:".length());
        } else {
            try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{android.provider.MediaStore.MediaColumns.DATA}, null, null, null)) {
                check(cursor != null && cursor.moveToFirst(), "Fixture path unavailable"); path = cursor.getString(0);
            }
        }
        String quoted = "'" + path.replace("'", "'\\''") + "'";
        String command = "touch -m " + (old ? "-t 202001010000.00 " : "") + quoted;
        String result = shellOutput(command + " 2>&1");
        if (old && store.fileModifiedAt(uri) > System.currentTimeMillis() - 60000) {
            Bundle diagnostic = new Bundle();
            diagnostic.putString("stream", "INFO: fixture timestamp needs privileged setup: " + result + "\n");
            instrumentation.sendStatus(0, diagnostic);
            result = shellOutput("su 0 " + command + " 2>&1");
        }
        long modified = store.fileModifiedAt(uri);
        check(modified > 0 && (old ? modified < System.currentTimeMillis() - 60000
                : modified >= System.currentTimeMillis() - 10000), "Fixture timestamp setup failed: " + result + ", timestamp=" + modified);
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
        shellOutput(command);
    }
    private String shellOutput(String command) throws IOException {
        java.io.File script = java.io.File.createTempFile("transfer-shell-", ".sh", context.getExternalFilesDir(null));
        try (OutputStream output = new java.io.FileOutputStream(script)) {
            output.write((command + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try (ParcelFileDescriptor result = instrumentation.getUiAutomation().executeShellCommand("sh " + script.getAbsolutePath());
                java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            if (!script.delete()) script.deleteOnExit();
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
