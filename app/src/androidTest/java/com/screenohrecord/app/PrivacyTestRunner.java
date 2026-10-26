package com.screenohrecord.app;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.media.MediaMetadataRetriever;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Switch;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Self-contained device checks: no external test SDK required. */
public final class PrivacyTestRunner extends Instrumentation {
    private volatile Activity resumedActivity;
    private volatile SettingsActivity settingsActivity;
    private boolean settingsOnly;
    private final java.util.concurrent.CopyOnWriteArrayList<Activity> appActivities =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final StringBuffer lifecycleTrace = new StringBuffer();
    private void trace(String event, Activity activity) {
        if (activity instanceof MainActivity) lifecycleTrace.append(event).append('@')
                .append(System.identityHashCode(activity)).append(' ');
    }
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityResumed(Activity activity) {
            if (activity instanceof SettingsActivity) settingsActivity = (SettingsActivity) activity;
            trace("resume", activity);
            if (activity instanceof MainActivity) resumedActivity = activity;
        }
        @Override public void onActivityCreated(Activity activity, Bundle state) {
            trace("create", activity);
            if (activity instanceof MainActivity) appActivities.add(activity);
        }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { trace("pause", activity); }
        @Override public void onActivityStopped(Activity activity) { trace("stop", activity); }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) {
            trace("destroy", activity);
            appActivities.remove(activity);
            if (activity == resumedActivity) resumedActivity = null;
        }
    };
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        settingsOnly = arguments != null && "true".equals(arguments.getString("settingsOnly"));
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        Activity activity = null;
        Application application = (Application) getTargetContext().getApplicationContext();
        application.registerActivityLifecycleCallbacks(lifecycle);
        try {
            String packageName = getTargetContext().getPackageName();
            PackageInfo info = getTargetContext().getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS | PackageManager.GET_ACTIVITIES);
            Set<String> permissions = new HashSet<>(Arrays.asList(info.requestedPermissions));
            check(permissions.contains(Manifest.permission.INTERNET), "Approved updater internet permission missing");
            check(permissions.contains(Manifest.permission.ACCESS_NETWORK_STATE), "Updater network constraint permission missing");
            check(permissions.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES), "Update install permission missing");
            check(!permissions.contains(Manifest.permission.CAMERA), "Camera permission present");
            check(!permissions.contains("android.permission.READ_MEDIA_VIDEO"), "Broad media-read permission present");
            check(!permissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE), "Legacy storage permission present");
            check(info.applicationInfo.targetSdkVersion == 36, "Build does not target Android 16");
            check(BuildConfig.VERSION_NAME.equals(info.versionName), "Wrong installed app version: " + info.versionName);
            check((info.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0, "Backup allowed");
            check(info.services.length == 3, "Unexpected service inventory");
            for (android.content.pm.ServiceInfo service : info.services) {
                if (service.name.endsWith("UpdateCheckJob")) check("android.permission.BIND_JOB_SERVICE".equals(service.permission), "Unprotected update job");
                else check(!service.exported, "Exported service: " + service.name);
            }
            for (android.content.pm.ActivityInfo declared : info.activities) {
                if (declared.name.endsWith("CaptureActivity")) check(!declared.exported, "Exported capture-consent host");
            }
            check(permissions.contains(Manifest.permission.SYSTEM_ALERT_WINDOW), "Overlay permission not declared");
            check(info.providers == null || info.providers.length == 0, "Unexpected content provider");
            testUpdateValidation();
            int[] portrait = RecordingConfig.chooseSize(1080, 2400, 720);
            check(portrait[0] == 720 && portrait[1] == 1600, "Portrait scaling failed");
            int[] landscape = RecordingConfig.chooseSize(2400, 1080, 720);
            check(landscape[0] == 1600 && landscape[1] == 720, "Landscape scaling failed");
            int[] odd = RecordingConfig.chooseSize(719, 1279, 1080);
            check(odd[0] == 718 && odd[1] == 1278, "Even-size/no-upscale policy failed");
            boolean rejected = false;
            try { RecordingConfig.chooseSize(0, 1, 720); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid dimensions accepted");
            check("01:01:01".equals(RecordingConfig.elapsed(3_661_000)), "Duration formatting failed");

            android.content.SharedPreferences defaults = getTargetContext().getSharedPreferences("capture_options", 0);
            defaults.edit().clear().commit();
            check(RecordingOptions.get(getTargetContext()).getBoolean("microphone", false), "Fresh microphone default missing");
            defaults.edit().remove("defaults_112").putBoolean("microphone", false).commit();
            check(RecordingOptions.get(getTargetContext()).getBoolean("microphone", false), "Upgrade microphone migration missing");
            defaults.edit().putBoolean("microphone", false).commit();
            check(!RecordingOptions.get(getTargetContext()).getBoolean("microphone", true), "Explicit microphone off was overridden");
            defaults.edit().putBoolean("microphone", true).commit();
            check(RecordingOptions.summary(getTargetContext()).contains("8.2 MB/min"), "Default size estimate incorrect");
            check(!SettingsActivity.isLocalTree(android.net.Uri.parse("content://cloud.example/tree/test")), "Cloud folder accepted");
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity launched = activity;
            runOnMainSync(() -> {
                Switch microphone = launched.findViewById(R.id.microphone);
                check(microphone != null && microphone.isChecked(), "Microphone is not on by default");
                check(launched.findViewById(R.id.start_recording).isEnabled(), "Start is not available");
                check(!launched.findViewById(R.id.stop_recording).isEnabled(), "Stop enabled without recording");
                microphone.setChecked(false);
            });
            append("PASS: approved updater permissions, protected job, private service, backup disabled, recording dimensions, duration and default UI");
            if (settingsOnly) {
                testRecordingSettings(launched);
                results.putString("stream", output.toString());
                finish(Activity.RESULT_OK, results);
                return;
            }

            // Cancel capture: no service and no new recording.
            awaitStart(launched);
            int beforeCancel = new RecordingStore(getTargetContext()).entries().size();
            tap(launched, R.id.start_recording);
            consent(false);
            SystemClock.sleep(700);
            check(!RecordingService.status().active(), "Capture started despite cancelled consent");
            check(new RecordingStore(getTargetContext()).entries().size() == beforeCancel,
                    "Cancelled consent created a video");
            append("PASS: Android capture-consent cancellation");

            // Silent recording, pause/resume and a decodable local MP4.
            awaitStart(launched);
            tap(launched, R.id.start_recording);
            consent(true);
            awaitState(RecordingService.State.RECORDING);
            SystemClock.sleep(2300);
            notificationAction("Pause");
            awaitState(RecordingService.State.PAUSED);
            long paused = RecordingService.status().duration;
            SystemClock.sleep(1100);
            check(RecordingService.status().duration == paused, "Paused timer kept advancing");
            notificationAction("Resume");
            awaitState(RecordingService.State.RECORDING);
            SystemClock.sleep(1700);
            notificationAction("Stop");
            awaitState(RecordingService.State.IDLE);
            verifyLatestVideo(false);
            append("PASS: silent recording, notification pause/resume/stop, stable pause timer and decoded local MP4");

            // Opt-in microphone path. Emulator permissions are granted by the test host.
            awaitStart(launched);
            check(getTargetContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED, "Test host did not grant microphone permission");
            runOnMainSync(() -> ((Switch) visibleActivity(launched).findViewById(R.id.microphone)).setChecked(true));
            tap(launched, R.id.start_recording);
            consent(true);
            awaitState(RecordingService.State.RECORDING);
            SystemClock.sleep(2600);
            tap(launched, R.id.stop_recording);
            awaitState(RecordingService.State.IDLE);
            verifyLatestVideo(true);
            append("PASS: opt-in microphone recording produces an AAC audio track");

            // Screen-off privacy stop releases the service and saves the completed segment.
            awaitStart(launched);
            runOnMainSync(() -> ((Switch) visibleActivity(launched).findViewById(R.id.microphone)).setChecked(false));
            tap(launched, R.id.start_recording);
            consent(true);
            awaitState(RecordingService.State.RECORDING);
            SystemClock.sleep(1800);
            shell("input keyevent KEYCODE_SLEEP");
            awaitState(RecordingService.State.IDLE);
            verifyLatestVideo(false);
            shell("input keyevent KEYCODE_WAKEUP");
            shell("input keyevent KEYCODE_MENU");
            append("PASS: screen-off stops capture and finalizes local video");

            // Start from a real overlay on the home screen, not an in-Activity test hook.
            shell("appops set com.screenohrecord.app SYSTEM_ALERT_WINDOW deny");
            activity = bringMainToFront(launched);
            tap(activity, R.id.floating_toggle);
            SystemClock.sleep(600);
            check(!FloatingControlsService.isVisible(), "Bubble appeared without overlay permission");
            shell("appops set com.screenohrecord.app SYSTEM_ALERT_WINDOW allow");
            shell("input keyevent KEYCODE_BACK");
            awaitBubble(true);
            check(!RecordingService.status().active(), "Showing bubble started a recording automatically");
            shell("input keyevent KEYCODE_HOME");
            Rect beforeDrag = overlayBounds("floating_bubble");
            int dragY = beforeDrag.centerY() > getTargetContext().getResources().getDisplayMetrics().heightPixels / 2
                    ? -200 : 200;
            drag(beforeDrag.centerX(), beforeDrag.centerY(), beforeDrag.centerX() - 100, beforeDrag.centerY() + dragY);
            Rect afterDrag = overlayBounds("floating_bubble");
            check(Math.abs(beforeDrag.top - afterDrag.top) > 50,
                    "Floating bubble did not move: before=" + beforeDrag + ", after=" + afterDrag);
            append("PASS: optional overlay permission, idle bubble outside app, and drag gesture");

            int beforeBubbleCancel = new RecordingStore(getTargetContext()).entries().size();
            overlayClick("floating_bubble");
            overlayClick("floating_start");
            consent(false);
            awaitRequestFinished();
            check(!RecordingService.status().active(), "Floating start ignored cancelled consent");
            check(new RecordingStore(getTargetContext()).entries().size() == beforeBubbleCancel,
                    "Cancelled floating consent created a recording");
            overlayClick("floating_bubble");
            overlayClick("floating_start");
            consent(true);
            awaitState(RecordingService.State.RECORDING);
            awaitRequestFinished();
            SystemClock.sleep(1600);
            overlayClick("floating_bubble");
            overlayClick("floating_pause");
            awaitState(RecordingService.State.PAUSED);
            overlayClick("floating_pause");
            awaitState(RecordingService.State.RECORDING);
            SystemClock.sleep(1200);
            overlayClick("floating_stop");
            awaitState(RecordingService.State.IDLE);
            verifyLatestVideo(false);
            overlayClick("floating_hide");
            awaitBubble(false);
            append("PASS: floating Start with Android consent, cancellation, pause/resume, Stop/save and Hide");

            activity = bringMainToFront(launched);
            tap(activity, R.id.floating_toggle);
            awaitBubble(true);
            shell("appops set com.screenohrecord.app SYSTEM_ALERT_WINDOW deny");
            awaitBubble(false);
            check(!RecordingService.status().active(), "Revoked idle overlay started recording");
            append("PASS: overlay permission revocation removes bubble and its idle service");
            testRecordingSettings(launched);

            results.putString("stream", output.toString());
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("stream", output + "FAIL: " + android.util.Log.getStackTraceString(error) + "\n");
            finish(Activity.RESULT_CANCELED, results);
        } finally {
            application.unregisterActivityLifecycleCallbacks(lifecycle);
            if (activity != null) {
                Activity launched = activity;
                runOnMainSync(launched::finish);
            }
        }
    }

    private final StringBuilder output = new StringBuilder();

    private void testUpdateValidation() throws Exception {
        check(!UpdateScheduler.enabled(getTargetContext()), "Weekly checks enabled without opt-in");
        check(UpdateScheduler.setEnabled(getTargetContext(), true), "Weekly scheduler rejected job");
        android.app.job.JobInfo job = getTargetContext().getSystemService(android.app.job.JobScheduler.class)
                .getPendingJob(UpdateScheduler.JOB_ID);
        check(job != null && job.isPersisted() && job.isPeriodic()
                && job.getIntervalMillis() == java.util.concurrent.TimeUnit.DAYS.toMillis(7), "Wrong weekly schedule");
        check(UpdateScheduler.setEnabled(getTargetContext(), false), "Could not disable weekly updates");
        check(getTargetContext().getSystemService(android.app.job.JobScheduler.class).getPendingJob(UpdateScheduler.JOB_ID) == null,
                "Disabled weekly job remains scheduled");
        check(UpdateClient.begin(), "Cannot acquire update gate while idle");
        runOnMainSync(() -> CaptureActivity.launch(getTargetContext(), false, 720));
        check(!CaptureActivity.isRequestActive() && !RecordingService.status().active(), "Recording launched during update operation");
        UpdateClient.end();
        java.io.File own = new java.io.File(getTargetContext().getApplicationInfo().sourceDir);
        String hash = UpdateClient.hash(own);
        org.json.JSONObject metadata = new org.json.JSONObject().put("schema", 1).put("versionCode", BuildConfig.VERSION_CODE)
                .put("versionName", BuildConfig.VERSION_NAME).put("size", own.length()).put("sha256", hash)
                .put("apkUrl", UpdateClient.REPOSITORY + "/releases/download/v" + BuildConfig.VERSION_NAME
                        + "/ScreenOhRecord-" + BuildConfig.VERSION_NAME + ".apk").put("notes", "Test release");
        UpdateClient.Release release = UpdateClient.parse(metadata.toString());
        UpdateClient.verify(getTargetContext(), own, release, false);
        boolean rejected = false;
        try { UpdateClient.verify(getTargetContext(), own, release, true); } catch (IOException expected) { rejected = true; }
        check(rejected, "Updater accepted same-version APK as an upgrade");
        rejected = false;
        try { UpdateClient.verify(getTargetContext(), own, new UpdateClient.Release(release.versionCode, release.versionName,
                release.apkUrl, "0".repeat(64), release.size, ""), false); } catch (IOException expected) { rejected = true; }
        check(rejected, "Updater accepted corrupt checksum");
        java.io.File wrongSigner = new java.io.File(getTargetContext().getExternalFilesDir(null), "update-wrong-signer.apk");
        check(wrongSigner.isFile(), "Host did not provide alternate-signer APK fixture");
        rejected = false;
        try { UpdateClient.verify(getTargetContext(), wrongSigner, new UpdateClient.Release(BuildConfig.VERSION_CODE,
                BuildConfig.VERSION_NAME, release.apkUrl, UpdateClient.hash(wrongSigner), wrongSigner.length(), ""), false); }
        catch (IOException expected) { rejected = expected.getMessage().contains("signing certificate"); }
        check(rejected, "Updater accepted alternate signing certificate");
        metadata.put("apkUrl", "https://example.com/ScreenOhRecord.apk");
        rejected = false;
        try { UpdateClient.parse(metadata.toString()); } catch (IOException expected) { rejected = true; }
        check(rejected, "Updater accepted foreign APK host/repository");
        check(!UpdateClient.allowedHost(new java.net.URL("http://github.com/test")), "Updater accepted cleartext redirect");
        check(!UpdateClient.allowedHost(new java.net.URL("https://github.com.evil.example/test")), "Updater accepted lookalike host");
        check(!UpdateClient.allowedHost(new java.net.URL("https://user:password@github.com/test")), "Updater accepted URL credentials");
        append("PASS: optional weekly scheduling/cancellation, update-recording exclusion, metadata URL/hash/version validation and installed signing identity");
    }

    private void deleteWithFloatingControls(Activity main) throws Exception {
        main = bringMainToFront(main);
        shell("appops set com.screenohrecord.app SYSTEM_ALERT_WINDOW allow");
        tap(main, R.id.floating_toggle);
        awaitBubble(true);
        int before = new RecordingStore(getTargetContext()).entries().size();
        overlayClick("floating_bubble"); overlayClick("floating_start"); consent(true);
        awaitState(RecordingService.State.RECORDING); awaitRequestFinished();
        check(!UpdateClient.begin(), "Update network gate acquired during recording");
        String uri = getTargetContext().getSharedPreferences("local_recordings", 0).getString("unfinished", null);
        check(uri != null, "Active recording has no owned output");
        SystemClock.sleep(1600);
        overlayClick("floating_bubble"); overlayClick("floating_discard");
        awaitState(RecordingService.State.IDLE); awaitBubble(false);
        check("Recording permanently deleted".equals(RecordingService.status().message), "Discard did not report deletion: " + RecordingService.status().message);
        check(new RecordingStore(getTargetContext()).entries().size() == before, "Discarded video entered history");
        boolean missing = false;
        try (ParcelFileDescriptor ignored = getTargetContext().getContentResolver().openFileDescriptor(android.net.Uri.parse(uri), "r")) { }
        catch (java.io.FileNotFoundException expected) { missing = true; }
        catch (IllegalArgumentException expected) { missing = expected.getMessage() != null && expected.getMessage().contains("Missing file"); }
        check(missing, "Discarded file still opens (deleted/trash check): " + uri);
        check(!getTargetContext().getSharedPreferences("local_recordings", 0).contains("unfinished"), "Discard retained cleanup marker");
        append("PASS: Delete and Close permanently removes active " + android.net.Uri.parse(uri).getAuthority()
                + " output, keeps history unchanged, closes bubble and excludes updater network access");
    }

    private void testAv1(Activity main) throws Exception {
        main = bringMainToFront(main); tap(main, R.id.recording_settings);
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while ((settingsActivity == null || !settingsActivity.hasWindowFocus()) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(settingsActivity != null && settingsActivity.hasWindowFocus(), "AV1 settings did not open");
        runOnMainSync(() -> ((android.widget.Spinner) settingsActivity.findViewById(R.id.settings_codec)).setSelection(2));
        waitForIdleSync();
        check("av1".equals(RecordingOptions.codec(getTargetContext())), "AV1 choice not retained");
        boolean supported = RecordingOptions.supportsCodec(getTargetContext(), "av1");
        runOnMainSync(() -> settingsActivity.finish());
        main = bringMainToFront(main); awaitStart(main); tap(main, R.id.start_recording); consent(true);
        if (supported) {
            awaitState(RecordingService.State.RECORDING); SystemClock.sleep(3500); notificationAction("Stop");
            awaitState(RecordingService.State.IDLE); verifyLatestVideo(true);
            verifyEncoding(new RecordingStore(getTargetContext()).entries().get(0), "video/av01", 720, 15);
            append("PASS: AV1 selection, actual encoded/decoded AV1 MP4 and microphone");
        } else {
            awaitRequestFinished(); awaitState(RecordingService.State.IDLE);
            check(RecordingService.status().message.contains("Unsupported recording settings"), "Unsupported AV1 did not give a clear error");
            append("PASS: AV1 selection retained; unsupported device encoder rejected before output creation");
        }
        RecordingOptions.get(getTargetContext()).edit().putString("codec", "h264").putBoolean("hevc", false).commit();
    }

    private void testRecordingSettings(Activity main) throws Exception {
        main = bringMainToFront(main);
        tap(main, R.id.recording_settings);
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while ((settingsActivity == null || !settingsActivity.hasWindowFocus()) && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100);
        check(settingsActivity != null && settingsActivity.hasWindowFocus(), "Settings did not open");
        RecordingOptions.get(getTargetContext()).edit().putInt("quality", 1080).putInt("fps", 30)
                .putInt("bitrate", 2_000_000).commit();
        boolean hevc = RecordingOptions.supportsHevc(getTargetContext());
        runOnMainSync(() -> {
            ((android.widget.Spinner) settingsActivity.findViewById(R.id.settings_resolution)).setSelection(1);
            ((android.widget.Spinner) settingsActivity.findViewById(R.id.settings_fps)).setSelection(1);
            ((android.widget.Spinner) settingsActivity.findViewById(R.id.settings_bitrate)).setSelection(4);
            ((android.widget.Spinner) settingsActivity.findViewById(R.id.settings_codec)).setSelection(hevc ? 1 : 0);
            ((Switch) settingsActivity.findViewById(R.id.settings_microphone)).setChecked(true);
        });
        waitForIdleSync();
        android.content.SharedPreferences preferences = RecordingOptions.get(getTargetContext());
        check(preferences.getInt("quality", 0) == 1080 && preferences.getInt("fps", 0) == 30
                && preferences.getInt("bitrate", 0) == 2_000_000, "Settings choices did not persist");
        shell("mkdir -p /sdcard/Movies/ScreenOhRecordSettingsTest");
        runOnMainSync(() -> settingsActivity.findViewById(R.id.settings_folder).performClick());
        clickSystemText("ScreenOhRecordSettingsTest");
        clickSystemText("Use this folder");
        clickSystemText("Allow");
        deadline = SystemClock.elapsedRealtime() + 8000;
        while (!preferences.contains("folder_uri") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(preferences.contains("folder_uri"), "Folder picker did not retain destination");
        android.net.Uri tree = android.net.Uri.parse(preferences.getString("folder_uri", ""));
        check(SettingsActivity.isLocalTree(tree), "Nonlocal destination persisted");
        runOnMainSync(() -> settingsActivity.finish());
        main = bringMainToFront(main);
        final Activity visible = main;
        runOnMainSync(() -> {
            check(((Switch) visible.findViewById(R.id.microphone)).isChecked(), "Main microphone did not refresh from Settings");
            check(((android.widget.Spinner) visible.findViewById(R.id.recording_quality)).getSelectedItemPosition() == 1,
                    "Main resolution did not refresh from Settings");
        });
        awaitStart(main);
        tap(main, R.id.start_recording);
        consent(true);
        awaitState(RecordingService.State.RECORDING);
        SystemClock.sleep(3500);
        notificationAction("Stop");
        awaitState(RecordingService.State.IDLE);
        verifyLatestVideo(true);
        RecordingStore store = new RecordingStore(getTargetContext());
        RecordingStore.Entry video = store.entries().get(0);
        check(SettingsActivity.LOCAL_AUTHORITY.equals(video.uri.getAuthority()), "Recording ignored selected local folder");
        verifyEncoding(video, hevc ? "video/hevc" : "video/avc", 1080, 30);
        deleteWithFloatingControls(main);
        append("PASS: settings UI persists 1080p/30fps/2Mbps/" + (hevc ? "HEVC" : "H.264")
                + ", system folder picker, microphone, local-folder MP4 and decoding");

        // A created-but-unfinished local document must be cleaned up without touching saved videos.
        RecordingStore.Output unfinished = store.create();
        unfinished.descriptor.close();
        store.recoverInterrupted();
        check(!getTargetContext().getSharedPreferences("local_recordings", 0).contains("unfinished"), "Local-folder recovery failed");
        verifyLatestVideo(true);
        getTargetContext().getSharedPreferences("local_recordings", 0).edit()
                .putString("unfinished", video.uri.toString()).putString("finalized_document", video.uri.toString()).commit();
        store.recoverInterrupted();
        verifyLatestVideo(true);
        store.delete(video);
        boolean deleted = false;
        try (ParcelFileDescriptor ignored = getTargetContext().getContentResolver().openFileDescriptor(video.uri, "r")) { }
        catch (java.io.FileNotFoundException expected) { deleted = true; }
        catch (IllegalArgumentException expected) {
            // ExternalStorageProvider wraps missing tree children in IllegalArgumentException.
            deleted = expected.getMessage() != null && expected.getMessage().contains("Missing file");
        }
        check(deleted, "Local-folder deletion left the video behind");
        getTargetContext().getContentResolver().releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        boolean denied = false;
        try { RecordingStore.Output unexpected = store.create(); unexpected.descriptor.close(); store.discard(unexpected.uri); }
        catch (IOException | SecurityException expected) { denied = true; }
        check(denied && preferences.contains("folder_uri"), "Revoked folder silently fell back to another location");
        preferences.edit().remove("folder_uri").remove("folder_label").putInt("quality", 720)
                .putInt("fps", 15).putInt("bitrate", 1_000_000).putBoolean("hevc", false)
                .putBoolean("microphone", true).commit();
        append("PASS: local-folder unfinished cleanup, saved-file preservation, deletion and revoked-access failure without fallback");
        main = bringMainToFront(main);
        awaitStart(main);
        tap(main, R.id.start_recording);
        consent(true);
        awaitState(RecordingService.State.RECORDING);
        SystemClock.sleep(3500);
        notificationAction("Stop");
        awaitState(RecordingService.State.IDLE);
        verifyLatestVideo(true);
        RecordingStore.Entry small = store.entries().get(0);
        check("media".equals(small.uri.getAuthority()), "Default Movies destination was not restored");
        verifyEncoding(small, "video/avc", 720, 15);
        append("PASS: small-file 720p/15fps/1Mbps defaults with microphone, default Movies restoration and decodable MP4");
        testLowBitrates(main);
        deleteWithFloatingControls(main);
        testAv1(main);
    }

    private void testLowBitrates(Activity main) throws Exception {
        int[] rates = {500_000, 350_000, 250_000};
        int[] indices = {2, 1, 0};
        String[] estimates = {"4.5 MB/min", "3.3 MB/min", "2.6 MB/min"};
        for (int i = 0; i < rates.length; i++) {
            main = bringMainToFront(main);
            tap(main, R.id.recording_settings);
            long deadline = SystemClock.elapsedRealtime() + 8000;
            while ((settingsActivity == null || !settingsActivity.hasWindowFocus())
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
            check(settingsActivity != null && settingsActivity.hasWindowFocus(), "Low-bitrate Settings did not open");
            final int selected = indices[i];
            runOnMainSync(() -> ((android.widget.Spinner) settingsActivity.findViewById(R.id.settings_bitrate)).setSelection(selected));
            waitForIdleSync();
            check(RecordingOptions.get(getTargetContext()).getInt("bitrate", 0) == rates[i], "Low bitrate was not persisted");
            String summary = RecordingOptions.summary(getTargetContext());
            check(summary.contains((rates[i] / 1000) + " kbps") && summary.contains(estimates[i]),
                    "Incorrect low-bitrate label/estimate: " + summary);
            runOnMainSync(() -> settingsActivity.finish());
            main = bringMainToFront(main);
            awaitStart(main);
            tap(main, R.id.start_recording);
            consent(true);
            awaitState(RecordingService.State.RECORDING);
            SystemClock.sleep(3000);
            notificationAction("Stop");
            awaitState(RecordingService.State.IDLE);
            verifyLatestVideo(true);
            RecordingStore.Entry video = new RecordingStore(getTargetContext()).entries().get(0);
            verifyEncoding(video, "video/avc", 720, 15);
            append("PASS: " + (rates[i] / 1000) + " kbps Settings persistence, correct size estimate and decodable 720p MP4 with microphone");
        }
        RecordingOptions.get(getTargetContext()).edit().putInt("bitrate", 1_000_000).commit();
    }

    private void verifyEncoding(RecordingStore.Entry entry, String mime, int shortEdge, int fps) throws Exception {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(getTargetContext(), entry.uri, null);
            boolean found = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                if (mime.equals(format.getString(android.media.MediaFormat.KEY_MIME))) {
                    check(Math.min(format.getInteger(android.media.MediaFormat.KEY_WIDTH),
                            format.getInteger(android.media.MediaFormat.KEY_HEIGHT)) == shortEdge, "Wrong output resolution: " + format);
                    // Surface capture can emit fewer frames on a static screen or a slow emulator.
                    // The setting requests a target rate; the MP4 reports the achieved average.
                    if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                        float actual = format.getNumber(android.media.MediaFormat.KEY_FRAME_RATE).floatValue();
                        check(actual > 0 && actual <= fps + 2, "Output exceeded requested frame rate: " + format);
                    }
                    append("ENCODED: " + format);
                    found = true;
                }
            }
            check(found, "Requested codec missing: " + mime);
        } finally { extractor.release(); }
    }

    private void clickSystemText(String wanted) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(wanted)) {
                        if (node.getText() != null && wanted.equalsIgnoreCase(node.getText().toString())) {
                            AccessibilityNodeInfo target = node;
                            for (int i = 0; i < 5 && target != null; i++, target = target.getParent())
                                if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                    SystemClock.sleep(500); return;
                                }
                        }
                    }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("System folder-picker action missing: " + wanted);
    }


    private void append(String value) {
        output.append(value).append('\n');
        Bundle progress = new Bundle();
        progress.putString("stream", value + "\n");
        sendStatus(1, progress);
    }

    private void tap(Activity activity, int id) {
        runOnMainSync(() -> check(visibleActivity(activity).findViewById(id).performClick(), "Control click failed: " + id));
    }

    private void notificationAction(String title) throws Exception {
        NotificationManager manager = getTargetContext().getSystemService(NotificationManager.class);
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                Notification.Action[] actions = item.getNotification().actions;
                if (actions == null) continue;
                for (Notification.Action action : actions) {
                    if (title.contentEquals(action.title)) { action.actionIntent.send(); return; }
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Recording notification action was not available: " + title);
    }

    private Activity visibleActivity(Activity fallback) {
        // Android 16 task capture can keep more than one resumed activity. Target
        // the focused window, not a stale paused instance's old enabled state.
        for (int i = appActivities.size() - 1; i >= 0; i--) {
            Activity candidate = appActivities.get(i);
            if (!candidate.isDestroyed() && candidate.hasWindowFocus()) return candidate;
        }
        return resumedActivity != null ? resumedActivity : fallback;
    }

    private Activity bringMainToFront(Activity fallback) {
        // startActivitySync waits forever when Android reuses an existing task's
        // activity. Use a launcher-equivalent action and an explicit focus deadline.
        shell("am start --activity-single-top -n com.screenohrecord.app/.MainActivity");
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            Activity[] focused = {null};
            runOnMainSync(() -> {
                Activity candidate = visibleActivity(fallback);
                if (candidate.hasWindowFocus() && !candidate.isDestroyed()) focused[0] = candidate;
            });
            if (focused[0] != null) return focused[0];
            SystemClock.sleep(100);
        }
        throw new AssertionError("Main recording controls did not gain focus");
    }

    private void awaitStart(Activity activity) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] enabled = {false};
            runOnMainSync(() -> enabled[0] = visibleActivity(activity).findViewById(R.id.start_recording).isEnabled());
            if (enabled[0] && !RecordingService.status().active()) return;
            SystemClock.sleep(150);
        }
        StringBuilder diagnostic = new StringBuilder();
        runOnMainSync(() -> {
            Activity current = visibleActivity(activity);
            diagnostic.append("activity=").append(System.identityHashCode(current))
                    .append(", focus=").append(current.hasWindowFocus())
                    .append(", enabled=").append(current.findViewById(R.id.start_recording).isEnabled())
                    .append(", visible=").append(current.findViewById(R.id.start_recording).getVisibility())
                    .append(", state=").append(RecordingService.status().state)
                    .append(", label=").append(((android.widget.TextView) current.findViewById(R.id.recording_status)).getText());
            diagnostic.append(", awaitingConsent=").append(CaptureActivity.isRequestActive());
        });
        throw new AssertionError("Start control did not return to ready: " + diagnostic + "; lifecycle=" + lifecycleTrace);
    }

    private void awaitState(RecordingService.State desired) {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (RecordingService.status().state == desired) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Expected " + desired + ", got " + RecordingService.status().state +
                ": " + RecordingService.status().message);
    }

    private void consent(boolean allow) {
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    String owner = String.valueOf(root.getPackageName());
                    if (owner.contains("systemui")) {
                        List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId(
                                allow ? "android:id/button1" : "android:id/button2");
                        for (AccessibilityNodeInfo button : buttons) {
                            boolean clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            button.recycle();
                            if (clicked) {
                                if (allow) selectTestAppIfNeeded();
                                return;
                            }
                        }
                    }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("Android screen-capture consent dialog was not found");
    }

    private void selectTestAppIfNeeded() {
        // Android 16 defaults to single-app sharing, followed by a system app selector.
        // Select only this synthetic test app; never another installed app's contents.
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (RecordingService.status().active()) return;
            if (!CaptureActivity.isRequestActive() && RecordingService.status().message.contains("Unsupported recording settings")) return;
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    if (String.valueOf(root.getPackageName()).contains("systemui")) {
                        for (AccessibilityNodeInfo label : root.findAccessibilityNodeInfosByText("ScreenOhRecord")) {
                            boolean ownLabel = label.getText() != null && "ScreenOhRecord".contentEquals(label.getText());
                            boolean ownPreview = label.getContentDescription() != null
                                    && "ScreenOhRecord".contentEquals(label.getContentDescription());
                            if (!ownLabel && !ownPreview) continue;
                            AccessibilityNodeInfo target = label;
                            for (int level = 0; target != null && level < 5; level++) {
                                if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
                                target = target.getParent();
                            }
                        }
                    }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("Android 16 did not complete consent for the selected test app; status="
                + RecordingService.status().message);
    }

    private void verifyLatestVideo(boolean audio) {
        List<RecordingStore.Entry> entries = new RecordingStore(getTargetContext()).entries();
        check(!entries.isEmpty(), "No local recording was saved: " + RecordingService.status().message);
        RecordingStore.Entry latest = entries.get(0);
        check(latest.bytes > 0, "Saved recording has no bytes");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(getTargetContext(), latest.uri);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            check(duration != null && Long.parseLong(duration) > 500, "MP4 has no valid duration");
            String hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            check(audio == "yes".equals(hasAudio), "Unexpected audio track: " + hasAudio);
            Bitmap frame = retriever.getFrameAtTime(0);
            check(frame != null && frame.getWidth() > 0, "Cannot decode the recorded video");
            frame.recycle();
        } finally { try { retriever.release(); } catch (IOException ignored) { } }
        check(!getTargetContext().getSharedPreferences("local_recordings", 0).contains("unfinished"),
                "Completed recording retained an unfinished marker");
    }

    private void awaitBubble(boolean wanted) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (FloatingControlsService.isVisible() == wanted) return;
            SystemClock.sleep(150);
        }
        throw new AssertionError("Floating service visibility did not become " + wanted);
    }

    private void awaitRequestFinished() {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!CaptureActivity.isRequestActive()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Capture-consent host did not finish");
    }

    private AccessibilityNodeInfo overlayNode(String id) {
        AccessibilityServiceInfo serviceInfo = getUiAutomation().getServiceInfo();
        serviceInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        getUiAutomation().setServiceInfo(serviceInfo);
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByViewId(
                            "com.screenohrecord.app:id/" + id);
                    for (AccessibilityNodeInfo match : matches) if (match.isVisibleToUser()) return match;
                } finally { root.recycle(); }
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("Floating control not found: " + id);
    }

    private Rect overlayBounds(String id) {
        AccessibilityNodeInfo node = overlayNode(id);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds); node.recycle();
        return bounds;
    }

    private void overlayClick(String id) {
        AccessibilityNodeInfo node = overlayNode(id);
        try { check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Floating control click failed: " + id); }
        finally { node.recycle(); }
        SystemClock.sleep(250);
    }

    private void drag(int x, int y, int endX, int endY) {
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        getUiAutomation().injectInputEvent(press, true); press.recycle();
        for (int step = 1; step <= 6; step++) {
            SystemClock.sleep(45);
            MotionEvent move = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    x + (endX - x) * step / 6f, y + (endY - y) * step / 6f, 0);
            getUiAutomation().injectInputEvent(move, true); move.recycle();
        }
        MotionEvent release = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, endX, endY, 0);
        getUiAutomation().injectInputEvent(release, true); release.recycle();
        SystemClock.sleep(350);
    }

    private void shell(String command) {
        try (ParcelFileDescriptor ignored = getUiAutomation().executeShellCommand(command)) {
            SystemClock.sleep(300);
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
}

