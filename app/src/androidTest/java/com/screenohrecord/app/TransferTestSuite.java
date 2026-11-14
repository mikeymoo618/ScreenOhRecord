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
        run(false);
    }

    void runLarge() throws Exception {
        run(true);
    }

    private void run(boolean large) throws Exception {
        SharedPreferences options = RecordingOptions.get(context);
        SharedPreferences records = context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE);
        SharedPreferences transfers = TransferSettings.preferences(context);
        SharedPreferences verification = context.getSharedPreferences("transfer_verification", Context.MODE_PRIVATE);
        Map<String, ?> oldOptions = new HashMap<>(options.getAll());
        Map<String, ?> oldRecords = new HashMap<>(records.getAll());
        Map<String, ?> oldTransfers = new HashMap<>(transfers.getAll());
        Map<String, ?> oldVerification = new HashMap<>(verification.getAll());
        try {
            transfers.edit().putBoolean("enabled", false).commit();
            instrumentation.runOnMainSync(() -> TransferScheduler.schedule(context));
            long stoppedBy = SystemClock.elapsedRealtime() + 15000;
            while (TransferEngine.busy() && SystemClock.elapsedRealtime() < stoppedBy) SystemClock.sleep(100);
            check(!TransferEngine.busy(), "Previous transfer worker did not stop for isolated test setup");
            options.edit().remove("folder_uri").remove("folder_label").commit();
            records.edit().clear().putString("transfer_scanned", "").commit();
            transfers.edit().clear().commit();
            verification.edit().clear().commit();
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
            if (large) {
                testLargeResume();
                testNativeInterruption();
                testServerPause();
                testBeyondEightMinutes();
                pass("large transfer suite complete");
                return;
            }
            testQueue();
            testDiscovery();
            testBroadDiscovery();
            testFolderScope();
            testDefaultFolderAliases();
            testFailedFileContinuation();
            testProtocol("ftp");
            testProtocol("smb");
            testSmbResume();
            testCorruptionAndReadOnly();
            testFolder();
            testWifi();
            testConcurrentRecording();
            testHundredFiles();
            testBusyStart();
            testImmediateDisable();
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
            restore(verification, oldVerification);
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
        check(shellOutput("test -d /sdcard/Movies/" + name + " && echo folder-ready").contains("folder-ready"),
                "Test directory was not created");
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

    private void testSmbResume() throws Exception {
        RecordingStore.Entry video = largeFixture(8 * 1048576L);
        TransferSettings.Config config = config("smb");
        TransferSettings.save(context, config);
        MessageDigest checksum = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < 32; i++) checksum.update(CONTENT);
        StringBuilder hex = new StringBuilder();
        for (byte value : checksum.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        String temporary = TransferEngine.remoteName(context, video, hex.toString()) + ".uploading";
        try (TransferConnection remote = new TransferConnection(context, config, () -> false)) {
            try (OutputStream output = remote.write(temporary)) {
                for (int i = 0; i < 4; i++) output.write(CONTENT);
            }
            remote.finishStream();
            check(remote.size(temporary) == 1048576, "SMB partial upload fixture has wrong size");
            try (java.io.InputStream input = remote.read(temporary, 1048576 - 1)) {
                check(input.read() == (CONTENT[CONTENT.length - 1] & 255) && input.read() == -1,
                        "SMB offset read did not seek correctly");
            }
        }
        java.util.concurrent.atomic.AtomicBoolean resumed = new java.util.concurrent.atomic.AtomicBoolean();
        check(TransferEngine.run(context, () -> false, (message, bytes, total) -> {
            if (message.contains("Resuming upload") && bytes == 1048576) resumed.set(true);
        }) == 1 && !present(video), "Partial SMB upload did not complete");
        check(resumed.get(), "SMB upload restarted at zero");
        pass("SMB offset reads and partial upload continuation preserve verified content and permanent local deletion");
    }

    private void testHundredFiles() throws Exception {
        List<RecordingStore.Entry> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            RecordingStore.Entry entry = fixture(CONTENT);
            age(entry); batch.add(entry);
        }
        TransferSettings.save(context, config("ftp"));
        Activity screen = instrumentation.startActivitySync(new Intent(context, TransfersActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        TransferEngine.end(); ownsGate = false;
        instrumentation.runOnMainSync(() -> screen.findViewById(R.id.transfer_start).performClick());
        long deadline = SystemClock.elapsedRealtime() + 20 * 60000L;
        boolean notificationSeen = false;
        boolean continuousModeSeen = false;
        while (SystemClock.elapsedRealtime() < deadline && batch.stream().anyMatch(this::present)) {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                JobInfo job = context.getSystemService(JobScheduler.class).getPendingJob(TransferScheduler.CONTINUOUS_JOB_ID);
                if (job != null && job.isUserInitiated()) continuousModeSeen = true;
            } else if (TransferService.running()) continuousModeSeen = true;
            android.service.notification.StatusBarNotification[] notifications =
                    context.getSystemService(android.app.NotificationManager.class).getActiveNotifications();
            for (android.service.notification.StatusBarNotification notification : notifications)
                if (notification.getId() == TransferNotifications.ID) notificationSeen = true;
            SystemClock.sleep(200);
        }
        check(batch.stream().noneMatch(this::present), "Continuous queue stopped before moving all 100 files");
        check(notificationSeen, "Continuous queue lacked its user-visible notification");
        check(continuousModeSeen, "Queue did not use Android's long-running transfer mechanism");
        awaitNativeIdle();
        acquireGate();
        instrumentation.runOnMainSync(screen::finish);
        check(store.transferEntries().isEmpty(), "100-file queue retained completed entries");
        pass("100-file queue drains sequentially in one continuous "
                + (android.os.Build.VERSION.SDK_INT >= 34 ? "user-initiated job" : "foreground service")
                + " with a visible notification, verified copies and permanent local deletion");
    }

    private void testImmediateDisable() throws Exception {
        TransferEngine.end(); ownsGate = false;
        Activity screen = instrumentation.startActivitySync(new Intent(context, TransfersActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for (int attempt = 0; attempt < 3; attempt++) {
            TransferSettings.save(context, config("ftp"));
            instrumentation.runOnMainSync(() -> {
                android.widget.Switch toggle = screen.findViewById(R.id.transfer_enabled);
                toggle.setChecked(true);
                screen.findViewById(R.id.transfer_start).performClick();
                toggle.setChecked(false);
            });
            SystemClock.sleep(7000);
            check(!TransferEngine.busy(), "Disabled transfer kept running");
            for (android.service.notification.StatusBarNotification notification : context
                    .getSystemService(android.app.NotificationManager.class).getActiveNotifications())
                check(notification.getId() != TransferNotifications.ID, "Disabled transfer notification retained");
        }
        instrumentation.runOnMainSync(screen::finish);
        acquireGate();
        pass("immediate disable after starting continuous moves is safe across three start/cancel races");
    }

    private void awaitNativeIdle() throws Exception {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(TransferScheduler.JOB_ID);
        scheduler.cancel(TransferScheduler.RETRY_JOB_ID);
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while ((TransferEngine.busy() || TransferService.running()
                || scheduler.getPendingJob(TransferScheduler.CONTINUOUS_JOB_ID) != null)
                && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(!TransferEngine.busy() && !TransferService.running()
                && scheduler.getPendingJob(TransferScheduler.CONTINUOUS_JOB_ID) == null,
                "Native transfer did not finish its lifecycle after draining the queue");
    }

    private void testBusyStart() throws Exception {
        RecordingStore.Entry video = largeFixture(8 * 1048576L);
        TransferSettings.save(context, config("ftp"));
        Activity screen = instrumentation.startActivitySync(new Intent(context, TransfersActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            screen.findViewById(R.id.transfer_start).performClick();
            screen.findViewById(R.id.transfer_start).performClick();
        });
        SystemClock.sleep(500);
        check(present(video), "Waiting continuous worker bypassed the existing operation gate");
        TransferEngine.end(); ownsGate = false;
        long deadline = SystemClock.elapsedRealtime() + 90000;
        while (present(video) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(200);
        check(!present(video), "Busy continuous-start request was lost");
        awaitNativeIdle();
        acquireGate();
        instrumentation.runOnMainSync(screen::finish);
        pass("repeated Move videos now requests wait for an existing operation and drain the saved queue without replacing active work");
    }

    private RecordingStore.Entry largeFixture(long bytes) throws Exception {
        RecordingStore.Output output = store.create();
        try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output.descriptor)) {
            for (long written = 0; written < bytes; written += CONTENT.length)
                stream.write(CONTENT, 0, (int) Math.min(CONTENT.length, bytes - written));
        }
        store.complete(output, bytes, 0);
        for (RecordingStore.Entry entry : store.transferEntries()) {
            if (entry.uri.equals(output.uri)) { fixtures.add(entry); age(entry); return entry; }
        }
        throw new AssertionError("Large fixture missing");
    }

    private void testLargeResume() throws Exception {
        RecordingStore.Entry video = largeFixture(64 * 1048576L);
        String folder = android.os.Build.VERSION.SDK_INT == 28 ? "slow9" : "slow";
        TransferSettings.save(context, config("ftp", true, folder, "screenohrecord-test", PASSWORD, 1));
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean uploading = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger uploadChecks = new AtomicInteger();
        try {
            TransferEngine.run(context, () -> stop.get() || (android.os.Build.VERSION.SDK_INT == 28
                    && uploading.get() && uploadChecks.incrementAndGet() >= 128), (message, bytes, total) -> {
                if (message.contains("\nUploading")) {
                    uploading.set(true);
                    if (android.os.Build.VERSION.SDK_INT != 28 && bytes >= 8 * 1048576L) stop.set(true);
                }
            });
            throw new AssertionError("Upload interruption was not exercised");
        } catch (IOException expected) {
            check(stop.get() || uploadChecks.get() >= 128, "Upload failed before the injected interruption: " + expected);
            check(present(video), "Interrupted upload removed local video");
        }
        java.util.concurrent.atomic.AtomicBoolean uploadResumed = new java.util.concurrent.atomic.AtomicBoolean();
        stop.set(false);
        try {
            TransferEngine.run(context, stop::get, (message, bytes, total) -> {
                if (message.contains("Resuming upload") && bytes > 0) uploadResumed.set(true);
                if (message.contains("\nVerifying server copy") && bytes >= 8 * 1048576L) stop.set(true);
            });
            throw new AssertionError("Verification interruption was not exercised");
        } catch (IOException expected) {
            check(stop.get(), "Upload failed before the injected verification interruption: " + expected);
            check(present(video), "Interrupted verification removed local video");
        }
        check(uploadResumed.get(), "Partial FTP upload restarted at zero");
        java.util.concurrent.atomic.AtomicBoolean verificationResumed = new java.util.concurrent.atomic.AtomicBoolean();
        check(TransferEngine.run(context, () -> false, (message, bytes, total) -> {
            if (message.contains("Resuming verification") && bytes > 0) verificationResumed.set(true);
        }) == 1 && !present(video), "Interrupted large video did not complete safely");
        check(verificationResumed.get(), "SHA-256 verification restarted at zero");
        check(context.getSharedPreferences("transfer_verification", Context.MODE_PRIVATE).getAll().isEmpty(),
                "Completed verification checkpoint retained");
        pass("64 MiB throttled FTP upload and SHA-256 verification resume from durable offsets after separate interruptions");
    }

    private void testNativeInterruption() throws Exception {
        RecordingStore.Entry video = largeFixture(64 * 1048576L);
        String folder = android.os.Build.VERSION.SDK_INT == 28 ? "slow9" : "slow";
        TransferSettings.Config config = config("ftp", true, folder, "screenohrecord-test", PASSWORD, 1);
        TransferSettings.save(context, config);
        Activity screen = instrumentation.startActivitySync(new Intent(context, TransfersActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        TransferEngine.end(); ownsGate = false;
        instrumentation.runOnMainSync(() -> screen.findViewById(R.id.transfer_start).performClick());
        long stopBy = SystemClock.elapsedRealtime() + 90000;
        boolean verifying = false;
        while (SystemClock.elapsedRealtime() < stopBy && present(video)) {
            if (TransferSettings.preferences(context).getString("status", "").contains("Verifying server copy")) {
                verifying = true; break;
            }
            SystemClock.sleep(100);
        }
        check(verifying, "Native transfer did not reach verification before interruption");
        instrumentation.runOnMainSync(() -> ((android.widget.Switch) screen.findViewById(R.id.transfer_enabled)).setChecked(false));
        long stoppedBy = SystemClock.elapsedRealtime() + 15000;
        while (TransferEngine.busy() && SystemClock.elapsedRealtime() < stoppedBy) SystemClock.sleep(100);
        check(!TransferEngine.busy() && present(video), "Disabled native transfer did not stop safely");
        SystemClock.sleep(1000);
        TransferSettings.save(context, config);
        instrumentation.runOnMainSync(() -> {
            ((android.widget.Switch) screen.findViewById(R.id.transfer_enabled)).setChecked(true);
            screen.findViewById(R.id.transfer_start).performClick();
        });
        long completeBy = SystemClock.elapsedRealtime() + 180000;
        while (present(video) && SystemClock.elapsedRealtime() < completeBy) SystemClock.sleep(200);
        check(!present(video), "Native queue did not recover after disable/re-enable");
        awaitNativeIdle();
        acquireGate();
        instrumentation.runOnMainSync(screen::finish);
        pass("native continuous transfer stops safely during verification and completes after re-enabling saved moves");
    }

    private void testBeyondEightMinutes() throws Exception {
        RecordingStore.Entry video = largeFixture(1024 * 1048576L);
        String folder = android.os.Build.VERSION.SDK_INT == 28 ? "slow9" : "slow";
        TransferSettings.save(context, config("ftp", true, folder, "screenohrecord-test", PASSWORD, 1));
        Activity screen = instrumentation.startActivitySync(new Intent(context, TransfersActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        TransferEngine.end(); ownsGate = false;
        long started = SystemClock.elapsedRealtime();
        instrumentation.runOnMainSync(() -> screen.findViewById(R.id.transfer_start).performClick());
        boolean visibleProgress = false;
        long nextReport = started;
        while (present(video) && SystemClock.elapsedRealtime() - started < 25 * 60000L) {
            String status = TransferSettings.preferences(context).getString("status", "");
            if (status.contains(video.name) && status.contains("MB")) visibleProgress = true;
            if (SystemClock.elapsedRealtime() >= nextReport) {
                Bundle report = new Bundle(); report.putString("stream", "INFO: gigabyte transfer " + status + "\n");
                instrumentation.sendStatus(0, report); nextReport = SystemClock.elapsedRealtime() + 30000;
            }
            SystemClock.sleep(500);
        }
        check(!present(video), "Native throttled gigabyte queue did not complete: "
                + TransferSettings.preferences(context).getString("status", ""));
        long elapsed = SystemClock.elapsedRealtime() - started;
        check(elapsed > 8 * 60000L, "Fixture did not exercise a transfer beyond the old eight-minute cutoff: " + elapsed);
        check(visibleProgress, "Large transfer did not publish byte progress");
        awaitNativeIdle();
        acquireGate();
        instrumentation.runOnMainSync(screen::finish);
        pass("native 1 GiB throttled FTP video completes beyond eight minutes with two full SHA-256 remote read-backs, byte progress and permanent local deletion; elapsed_ms=" + elapsed);
    }

    private void testServerPause() throws Exception {
        RecordingStore.Entry video = fixture(CONTENT);
        age(video);
        TransferSettings.save(context, config("ftp", true, "pause", "screenohrecord-test", PASSWORD, 1));
        long started = SystemClock.elapsedRealtime();
        check(TransferEngine.run(context, () -> false) == 1 && !present(video), "15-second FTP pauses interrupted a healthy transfer");
        check(SystemClock.elapsedRealtime() - started >= 30000, "Server pause fixture did not delay both verification reads");
        pass("temporary 15-second FTP server pauses recover through both verification reads without premature timeout");
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
        awaitNativeIdle();
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
