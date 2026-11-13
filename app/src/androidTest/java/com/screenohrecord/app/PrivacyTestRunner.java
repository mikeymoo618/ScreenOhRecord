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
    private boolean transfersOnly;
    private boolean lanFtpOnly;
    private String transferHost = "10.0.2.2";
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
        transfersOnly = arguments != null && "true".equals(arguments.getString("transfersOnly"));
        lanFtpOnly = arguments != null && "true".equals(arguments.getString("lanFtpOnly"));
        if (arguments != null) transferHost = arguments.getString("transferHost", "10.0.2.2");
        start();
    }

    @Override public void onStart() {
        Bundle results = new Bundle();
        Activity activity = null;
        Application application = (Application) getTargetContext().getApplicationContext();
        application.registerActivityLifecycleCallbacks(lifecycle);
        try {
            if (lanFtpOnly) {
                new LanFtpTest(this).run();
                results.putString("stream", "PASS: LAN FTP test complete\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            if (transfersOnly) {
                new TransferTestSuite(this, transferHost).run();
                results.putString("stream", "PASS: transfer suite complete\n");
                finish(Activity.RESULT_OK, results);
                return;
            }
            String packageName = getTargetContext().getPackageName();
            PackageInfo info = getTargetContext().getPackageManager().getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS | PackageManager.GET_ACTIVITIES);
            Set<String> permissions = new HashSet<>(Arrays.asList(info.requestedPermissions));
            check(permissions.contains(Manifest.permission.INTERNET), "Approved updater internet permission missing");
            check(permissions.contains(Manifest.permission.ACCESS_NETWORK_STATE), "Updater network constraint permission missing");
            check(permissions.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES), "Update install permission missing");
            check(!permissions.contains(Manifest.permission.CAMERA), "Camera permission present");
            check(!permissions.contains("android.permission.READ_MEDIA_VIDEO"), "Broad media-read permission present");
            if (android.os.Build.VERSION.SDK_INT < 29) {
                check(permissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE)
                        && permissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE), "Android 9 storage permissions missing");
            } else {
                check(!permissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        && !permissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE), "Legacy storage permission applies on modern Android");
            }
            check(info.applicationInfo.targetSdkVersion == 36, "Build does not target Android 16");
            check(BuildConfig.VERSION_NAME.equals(info.versionName), "Wrong installed app version: " + info.versionName);
            check((info.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0, "Backup allowed");
            check(info.services.length == 4, "Unexpected service inventory");
            for (android.content.pm.ServiceInfo service : info.services) {
                if (service.name.endsWith("UpdateCheckJob") || service.name.endsWith("TransferJob"))
                    check("android.permission.BIND_JOB_SERVICE".equals(service.permission), "Unprotected background job");
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
            shell("appops set com.screenohrecord.app SYSTEM_ALERT_WINDOW deny");
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity initial = activity;
            check(!FloatingControlsService.isVisible(), "Automatic bubble ignored denied overlay permission");
            runOnMainSync(() -> {
                Switch microphone = initial.findViewById(R.id.microphone);
                check(microphone != null && microphone.isChecked(), "Microphone is not on by default");
                check(initial.findViewById(R.id.start_recording).isEnabled(), "Start is not available");
                check(!initial.findViewById(R.id.stop_recording).isEnabled(), "Stop enabled without recording");
                microphone.setChecked(false);
            });
            append("PASS: approved updater permissions, protected job, private service, backup disabled, recording dimensions, duration and default UI");
            if (android.os.Build.VERSION.SDK_INT == 28) {
                testAndroid9(initial);
                results.putString("stream", output.toString());
                finish(Activity.RESULT_OK, results);
                return;
            }
            activity = testFloatingOpenApp(initial);
            Activity launched = activity;
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
            awaitBubble(true);
            check(!RecordingService.status().active(), "Automatic floating controls started capture");
            tap(activity, R.id.floating_toggle);
            awaitBubble(false);
            SystemClock.sleep(1000);
            check(!FloatingControlsService.isVisible(), "Hide did not last while main screen stayed open");
            shell("input keyevent KEYCODE_HOME");
            activity = bringMainToFront(launched);
            awaitBubble(true);
            append("PASS: floating controls appear automatically on return, Hide lasts until return, no automatic capture");
            shell("appops set com.screenohrecord.app SYSTEM_ALERT_WINDOW deny");
            awaitBubble(false);
            shell("input keyevent KEYCODE_HOME");
            activity = bringMainToFront(launched);
            SystemClock.sleep(600);
            check(!FloatingControlsService.isVisible(), "Resume bypassed revoked overlay permission");
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
        String zeroHash = "0000000000000000000000000000000000000000000000000000000000000000";
        try { UpdateClient.verify(getTargetContext(), own, new UpdateClient.Release(release.versionCode, release.versionName,
                release.apkUrl, zeroHash, release.size, ""), false); } catch (IOException expected) { rejected = true; }
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
        shell("appops set com.screenohrecord.app SYSTEM_ALERT_WINDOW allow");
        shell("input keyevent KEYCODE_HOME");
        main = bringMainToFront(main);
        awaitBubble(true);
        int before = new RecordingStore(getTargetContext()).entries().size();
        overlayClick("floating_bubble"); overlayClick("floating_start"); consent(true);
        awaitState(RecordingService.State.RECORDING); awaitRequestFinished();
        check(!UpdateClient.begin(), "Update network gate acquired during recording");
        String uri = getTargetContext().getSharedPreferences("local_recordings", 0).getString("unfinished", null);
        check(uri != null, "Active recording has no owned output");
        SystemClock.sleep(1600);
        overlayClick("floating_bubble");
        check("Stop and Delete".contentEquals(overlayNode("floating_discard").getText()), "Old floating delete label remains");
        overlayClick("floating_discard");
        awaitState(RecordingService.State.IDLE); awaitBubble(true);
        check(overlayNode("floating_start").isVisibleToUser(), "Floating menu closed after Stop and Delete");
        check("Recording permanently deleted".equals(RecordingService.status().message), "Discard did not report deletion: " + RecordingService.status().message);
        check(new RecordingStore(getTargetContext()).entries().size() == before, "Discarded video entered history");
        boolean missing = false;
        try (ParcelFileDescriptor ignored = getTargetContext().getContentResolver().openFileDescriptor(android.net.Uri.parse(uri), "r")) { }
        catch (java.io.FileNotFoundException expected) { missing = true; }
        catch (IllegalArgumentException expected) { missing = expected.getMessage() != null && expected.getMessage().contains("Missing file"); }
        check(missing, "Discarded file still opens (deleted/trash check): " + uri);
        check(!getTargetContext().getSharedPreferences("local_recordings", 0).contains("unfinished"), "Discard retained cleanup marker");
        append("PASS: Stop and Delete permanently removes active " + android.net.Uri.parse(uri).getAuthority()
                + " output, keeps history unchanged, leaves floating menu open and excludes updater network access");
        overlayClick("floating_hide"); awaitBubble(false);
        testRestartWithFloatingControls(main);
    }

    private void awaitRestartConsent() {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (!CaptureActivity.isRequestActive() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(CaptureActivity.isRequestActive(), "Restart did not request fresh Android capture consent");
        check(!RecordingService.status().active(), "New capture began before consent");
        check(!UpdateClient.begin(), "Updater gate opened during restart consent");
    }

    private Activity awaitMainForeground() {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        Activity[] focused = {null};
        do {
            runOnMainSync(() -> {
                Activity candidate = visibleActivity(null);
                if (candidate instanceof MainActivity && candidate.hasWindowFocus() && !candidate.isDestroyed()) {
                    focused[0] = candidate;
                }
            });
            if (focused[0] != null) return focused[0];
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Open app did not bring MainActivity into view");
    }

    private void assertOverlayControlHidden(String id) {
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId("com.screenohrecord.app:id/" + id)) {
                    check(!node.isVisibleToUser(), "Unexpected visible floating control: " + id);
                }
            } finally { root.recycle(); }
        }
    }

    private Activity testFloatingOpenApp(Activity initial) throws Exception {
        shell("appops set com.screenohrecord.app SYSTEM_ALERT_WINDOW allow");
        shell("input keyevent KEYCODE_HOME");
        bringMainToFront(initial); awaitBubble(true);
        shell("input keyevent KEYCODE_HOME");
        overlayClick("floating_bubble");
        Rect startBounds = overlayBounds("floating_start");
        Rect openBounds = overlayBounds("floating_open_app");
        Rect hideBounds = overlayBounds("floating_hide");
        check(startBounds.bottom <= openBounds.top && openBounds.bottom <= hideBounds.top,
                "Idle menu order must be Start recording, Open app, Hide bubble");
        overlayClick("floating_open_app");
        Activity opened = awaitMainForeground();
        check(opened == initial, "Open app duplicated the existing main activity");
        awaitBubble(true);
        overlayNode("floating_bubble").recycle();
        assertOverlayControlHidden("floating_open_app");

        // Keep the overlay service, then exercise opening with no remaining app task.
        runOnMainSync(opened::finishAndRemoveTask);
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (!opened.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(opened.isDestroyed(), "Existing main task did not finish");
        shell("input keyevent KEYCODE_HOME");
        overlayClick("floating_bubble"); overlayClick("floating_open_app");
        Activity fresh = awaitMainForeground();
        check(fresh != opened, "Open app did not create a fresh activity after task removal");
        assertOverlayControlHidden("floating_open_app");
        overlayClick("floating_bubble"); overlayClick("floating_open_app");
        check(awaitMainForeground() == fresh, "Open app duplicated an already foreground activity");
        check(!RecordingService.status().active() && !CaptureActivity.isRequestActive(), "Open app triggered capture");
        overlayClick("floating_bubble"); overlayClick("floating_hide"); awaitBubble(false);
        shell("appops set com.screenohrecord.app SYSTEM_ALERT_WINDOW deny");
        append("PASS: idle Open app order, existing-task foregrounding, fresh launch, repeated tap reuse and collapsed menu");
        return fresh;
    }

    private String activeOutputUri() {
        String uri = getTargetContext().getSharedPreferences("local_recordings", 0).getString("unfinished", null);
        check(uri != null, "Recording has no owned output");
        return uri;
    }

    private void assertRecordingGone(String uri) throws Exception {
        boolean missing = false;
        try (ParcelFileDescriptor ignored = getTargetContext().getContentResolver().openFileDescriptor(android.net.Uri.parse(uri), "r")) { }
        catch (java.io.FileNotFoundException expected) { missing = true; }
        catch (IllegalArgumentException expected) { missing = expected.getMessage() != null && expected.getMessage().contains("Missing file"); }
        check(missing, "Discarded recording still opens: " + uri);
    }

    private void testRestartWithFloatingControls(Activity main) throws Exception {
        shell("input keyevent KEYCODE_HOME");
        main = bringMainToFront(main); awaitBubble(true);
        RecordingStore store = new RecordingStore(getTargetContext());
        int before = store.entries().size();
        android.content.SharedPreferences options = RecordingOptions.get(getTargetContext());
        int quality = options.getInt("quality", 720), fps = options.getInt("fps", 15);
        String codec = RecordingOptions.codec(getTargetContext());
        overlayClick("floating_bubble"); overlayClick("floating_start"); consent(true);
        awaitState(RecordingService.State.RECORDING); awaitRequestFinished(); SystemClock.sleep(1600);
        String oldUri = activeOutputUri();
        overlayClick("floating_bubble");
        assertOverlayControlHidden("floating_open_app");
        overlayClick("floating_pause"); awaitState(RecordingService.State.PAUSED);
        assertOverlayControlHidden("floating_open_app");
        check("Stop and Restart".contentEquals(overlayNode("floating_restart").getText()), "Restart action label missing");
        overlayClick("floating_restart"); awaitRestartConsent();
        check(RecordingService.status().discardSucceeded, "Restart did not confirm deletion");
        assertRecordingGone(oldUri);
        check(store.entries().size() == before, "Restart published discarded recording in history");
        check(!getTargetContext().getSharedPreferences("local_recordings", 0).contains("unfinished"),
                "Restart retained old recovery marker before consent");
        consent(true); awaitState(RecordingService.State.RECORDING); awaitRequestFinished();
        String freshUri = activeOutputUri();
        check(!oldUri.equals(freshUri), "Restart reused old output URI");
        SystemClock.sleep(3000); notificationAction("Stop"); awaitState(RecordingService.State.IDLE);
        RecordingStore.Entry saved = store.entries().get(0);
        check(saved.uri.toString().equals(freshUri) && store.entries().size() == Math.min(50, before + 1),
                "Restart did not save exactly the fresh output");
        verifyLatestVideo(options.getBoolean("microphone", true));
        verifyEncoding(saved, "av1".equals(codec) ? "video/av01" : "hevc".equals(codec) ? "video/hevc" : "video/avc", quality, fps);
        store.delete(saved);
        int afterSavedCleanup = store.entries().size();

        // Exercise the action while actively recording and cancel its fresh consent.
        overlayClick("floating_bubble"); overlayClick("floating_start"); consent(true);
        awaitState(RecordingService.State.RECORDING); awaitRequestFinished(); SystemClock.sleep(1600);
        String cancelledUri = activeOutputUri();
        overlayClick("floating_bubble"); overlayClick("floating_restart"); awaitRestartConsent();
        assertRecordingGone(cancelledUri);
        consent(false); awaitRequestFinished(); awaitState(RecordingService.State.IDLE); awaitBubble(true);
        SystemClock.sleep(700);
        check(!CaptureActivity.isRequestActive() && !RecordingService.status().active(), "Cancelled restart retried or began recording");
        check(store.entries().size() == afterSavedCleanup, "Cancelled restart changed recording history");
        check(!getTargetContext().getSharedPreferences("local_recordings", 0).contains("unfinished"), "Cancelled restart left an output marker");
        overlayClick("floating_bubble"); overlayClick("floating_hide"); awaitBubble(false);
        append("PASS: " + android.net.Uri.parse(oldUri).getAuthority()
                + " Stop and Restart from paused/recording, permanent deletion, fresh consent/output, retained settings, cancellation and updater exclusion");
    }

    private void testRestartDeletionFailure(Activity main, android.net.Uri tree) throws Exception {
        main = bringMainToFront(main); awaitStart(main);
        tap(main, R.id.start_recording); consent(true);
        awaitState(RecordingService.State.RECORDING); awaitRequestFinished(); SystemClock.sleep(1600);
        String uri = activeOutputUri();
        getTargetContext().getContentResolver().releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        overlayClick("floating_bubble"); overlayClick("floating_restart"); awaitState(RecordingService.State.IDLE);
        SystemClock.sleep(1000);
        check(!RecordingService.status().discardSucceeded && !CaptureActivity.isRequestActive(),
                "Failed deletion requested a new capture");
        check(uri.equals(activeOutputUri()), "Failed deletion lost recovery marker");
        check(RecordingService.status().message.contains("Could not delete"), "Deletion failure not reported");
        check(overlayNode("floating_start").isVisibleToUser(), "Deletion failure closed floating menu");
        overlayClick("floating_hide"); awaitBubble(false);

        // Restore the grant through Android's real folder picker, then clean only our failed fixture.
        main = bringMainToFront(main); awaitStart(main); tap(main, R.id.recording_settings);
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while ((settingsActivity == null || !settingsActivity.hasWindowFocus()) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(settingsActivity != null && settingsActivity.hasWindowFocus(), "Recovery settings not visible");
        runOnMainSync(() -> settingsActivity.findViewById(R.id.settings_folder).performClick());
        clickSystemText("ScreenOhRecordSettingsTest"); clickSystemText("Use this folder"); clickSystemText("Allow");
        new RecordingStore(getTargetContext()).recoverInterrupted();
        check(!getTargetContext().getSharedPreferences("local_recordings", 0).contains("unfinished"), "Restored permission did not clean failed fixture");
        assertRecordingGone(uri);
        // A folder-picker result also carries a temporary grant tied to its task.
        // End that task so the later persistent-grant revocation test has no
        // remaining temporary permission masking its result.
        Activity grantHost = settingsActivity;
        runOnMainSync(grantHost::finishAndRemoveTask);
        deadline = SystemClock.elapsedRealtime() + 8000;
        while (!grantHost.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(grantHost.isDestroyed(), "Folder grant task did not finish");
        bringMainToFront(main);
        append("PASS: Stop and Restart deletion failure blocks new consent, retains cleanup marker/menu, and recovers after folder permission restored");
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

    private void testAndroid9(Activity main) throws Exception {
        for (android.media.MediaCodecInfo codecInfo : new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!codecInfo.isEncoder() || !Arrays.asList(codecInfo.getSupportedTypes()).contains("video/avc")) continue;
            android.media.MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType("video/avc");
            android.media.MediaFormat requested = android.media.MediaFormat.createVideoFormat("video/avc", 720, 1280);
            requested.setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 15);
            requested.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 1_000_000);
            requested.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            append("INFO: Android 9 AVC encoder " + codecInfo.getName() + " colors=" + Arrays.toString(capabilities.colorFormats)
                    + " widths=" + capabilities.getVideoCapabilities().getSupportedWidths()
                    + " heights=" + capabilities.getVideoCapabilities().getSupportedHeights()
                    + " rates=" + capabilities.getVideoCapabilities().getSupportedFrameRates()
                    + " bitrates=" + capabilities.getVideoCapabilities().getBitrateRange()
                    + " requested=" + capabilities.isFormatSupported(requested));
        }
        RecordingStore store = new RecordingStore(getTargetContext());
        android.content.SharedPreferences options = RecordingOptions.get(getTargetContext());
        check(RecordingStore.needsLegacyStoragePermission(getTargetContext()), "Android 9 default storage permission was not required");
        awaitStart(main); tap(main, R.id.start_recording); clickSystemText("Deny");
        awaitRequestFinished();
        check(!RecordingService.status().active() && !getTargetContext().getSharedPreferences("local_recordings", 0).contains("unfinished"),
                "Denied storage permission created an output or recording");
        awaitStart(main); tap(main, R.id.start_recording); clickSystemText("Allow"); consent(true);
        awaitState(RecordingService.State.RECORDING); awaitRequestFinished();
        check(!RecordingStore.needsLegacyStoragePermission(getTargetContext()), "Storage permission was not granted");
        SystemClock.sleep(2500); notificationAction("Pause"); awaitState(RecordingService.State.PAUSED);
        notificationAction("Resume"); awaitState(RecordingService.State.RECORDING);
        SystemClock.sleep(1600); notificationAction("Stop"); awaitState(RecordingService.State.IDLE);
        verifyLatestVideo(false);
        RecordingStore.Entry silent = store.entries().get(0);
        try (android.database.Cursor cursor = getTargetContext().getContentResolver().query(silent.uri,
                new String[]{android.provider.MediaStore.Video.Media.DATA}, null, null, null)) {
            check(cursor != null && cursor.moveToFirst(), "Legacy video has no MediaStore row");
            check(cursor.getString(0).contains("/Movies/ScreenOhRecord/") && new java.io.File(cursor.getString(0)).isFile(),
                    "Legacy recording is not stored in Movies/ScreenOhRecord");
        }
        append("PASS: Android 9 storage denial/grant, public Movies MP4, notification pause/resume/stop and silent playback");
        testHistoryPreviews(main, silent);

        final Activity visible = bringMainToFront(main);
        runOnMainSync(() -> ((Switch) visible.findViewById(R.id.microphone)).setChecked(true));
        awaitStart(visible); tap(visible, R.id.start_recording); consent(true);
        awaitState(RecordingService.State.RECORDING); awaitRequestFinished(); SystemClock.sleep(3000);
        notificationAction("Stop"); awaitState(RecordingService.State.IDLE); verifyLatestVideo(true);
        RecordingStore.Entry microphoneVideo = store.entries().get(0);
        android.content.SharedPreferences history = getTargetContext().getSharedPreferences("local_recordings", 0);
        history.edit().putString("unfinished", microphoneVideo.uri.toString())
                .putString("finalized_document", microphoneVideo.uri.toString()).commit();
        store.recoverInterrupted();
        check(!history.contains("unfinished") && store.availability(microphoneVideo) == RecordingStore.Availability.PRESENT,
                "Recovery removed a finalized Android 9 recording");
        RecordingStore.Output interrupted = store.create(); interrupted.descriptor.close();
        store.recoverInterrupted(); assertRecordingGone(interrupted.uri.toString());
        check(!history.contains("unfinished"), "Android 9 unfinished cleanup marker retained");
        verifyLatestVideo(true);
        append("PASS: Android 9 AAC microphone, finalized-file preservation and interrupted legacy-output cleanup");

        try (android.database.Cursor cursor = getTargetContext().getContentResolver().query(microphoneVideo.uri,
                new String[]{android.provider.MediaStore.Video.Media.DATA}, null, null, null)) {
            check(cursor != null && cursor.moveToFirst(), "Missing legacy file path");
            check(new java.io.File(cursor.getString(0)).delete(), "Could not remove own legacy test file outside MediaStore");
        }
        check(store.availability(microphoneVideo) == RecordingStore.Availability.MISSING,
                "Stale Android 9 MediaStore row hid a missing physical file");
        check(store.pruneMissing().contains(microphoneVideo.uri), "Missing legacy file retained in history");
        getTargetContext().getContentResolver().delete(microphoneVideo.uri, null, null);
        testExternalDeletion(main);
        append("PASS: Android 9 missing physical-file detection and stale MediaStore history cleanup");
        deleteWithFloatingControls(main);

        options.edit().putString("codec", "av1").commit();
        check(!RecordingOptions.supportsCodec(getTargetContext(), "av1"), "AV1 incorrectly supported on Android 9");
        main = bringMainToFront(main); awaitStart(main); tap(main, R.id.start_recording); consent(true);
        awaitRequestFinished(); awaitState(RecordingService.State.IDLE);
        check(RecordingService.status().message.contains("AV1 recording requires Android 13") && !history.contains("unfinished"),
                "Unavailable Android 9 codec did not fail cleanly");
        options.edit().putString("codec", "h264").commit();
        append("PASS: Android 9 unsupported AV1 fails before creating a recording");

        RecordingStore.Entry retained = historyFixture(store);
        shell("mkdir -p /sdcard/Movies/SR9");
        main = bringMainToFront(main); awaitStart(main); tap(main, R.id.recording_settings);
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while ((settingsActivity == null || !settingsActivity.hasWindowFocus()) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(settingsActivity != null && settingsActivity.hasWindowFocus(), "Android 9 settings unavailable");
        runOnMainSync(() -> settingsActivity.findViewById(R.id.settings_folder).performClick());
        clickSystemText("SR9"); clickSystemText("Select");
        deadline = SystemClock.elapsedRealtime() + 8000;
        while (!options.contains("folder_uri") && SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) try {
                boolean allow = root.findAccessibilityNodeInfosByText("Allow").stream()
                        .anyMatch(node -> "Allow".equalsIgnoreCase(String.valueOf(node.getText())));
                if (allow) clickSystemText("Allow");
            } finally { root.recycle(); }
            SystemClock.sleep(100);
        }
        check(options.contains("folder_uri"), "Android 9 folder grant not retained");
        runOnMainSync(() -> settingsActivity.finish());
        main = bringMainToFront(main);
        shell("appops set com.screenohrecord.app READ_EXTERNAL_STORAGE ignore");
        shell("appops set com.screenohrecord.app WRITE_EXTERNAL_STORAGE ignore");
        try {
            check(!RecordingStore.needsLegacyStoragePermission(getTargetContext()), "Chosen local folder requested global storage access");
            check(store.availability(retained) == RecordingStore.Availability.UNAVAILABLE && !store.pruneMissing().contains(retained.uri),
                    "Blocked global storage access removed a saved history entry");
            awaitStart(main); tap(main, R.id.start_recording); consent(true);
            awaitState(RecordingService.State.RECORDING); awaitRequestFinished(); SystemClock.sleep(3000);
            notificationAction("Stop"); awaitState(RecordingService.State.IDLE); verifyLatestVideo(true);
            RecordingStore.Entry document = store.entries().get(0);
            check(SettingsActivity.LOCAL_AUTHORITY.equals(document.uri.getAuthority()), "Android 9 ignored chosen folder");
            testHistoryPreviews(main, document);
        } finally {
            shell("appops set com.screenohrecord.app READ_EXTERNAL_STORAGE allow");
            shell("appops set com.screenohrecord.app WRITE_EXTERNAL_STORAGE allow");
        }
        store.delete(retained);
        options.edit().remove("folder_uri").remove("folder_label").commit();
        append("PASS: Android 9 selected-folder recording and previews with global storage access blocked, retained inaccessible history");
        append("PASS: Android 9 compatibility complete");
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
        java.util.Map<String, ?> selectedPreferences = new java.util.HashMap<>(preferences.getAll());
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
        check(preferences.getAll().equals(selectedPreferences), "Recording changed the selected Android 16 settings");
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
        testHistoryPreviews(main, video);
        testExternalDeletion(main);
        boolean deleted = false;
        try (ParcelFileDescriptor ignored = getTargetContext().getContentResolver().openFileDescriptor(video.uri, "r")) { }
        catch (java.io.FileNotFoundException expected) { deleted = true; }
        catch (IllegalArgumentException expected) {
            // ExternalStorageProvider wraps missing tree children in IllegalArgumentException.
            deleted = expected.getMessage() != null && expected.getMessage().contains("Missing file");
        }
        check(deleted, "Local-folder deletion left the video behind");
        testRestartDeletionFailure(main, tree);
        RecordingStore.Entry inaccessible = historyFixture(store);
        getTargetContext().getContentResolver().releasePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        check(store.availability(inaccessible) == RecordingStore.Availability.UNAVAILABLE,
                "Revoked storage permission mistaken for deletion: " + store.availability(inaccessible));
        check(!store.pruneMissing().contains(inaccessible.uri), "Revoked-access recording pruned");
        try { store.delete(inaccessible); throw new AssertionError("Revoked-access deletion unexpectedly succeeded"); }
        catch (RuntimeException expected) { }
        check(store.entries().stream().anyMatch(item -> item.uri.equals(inaccessible.uri)),
                "Failed deletion removed inaccessible recording history");
        append("PASS: revoked-folder access preserves history during reconciliation and failed deletion");
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
        testHistoryPreviews(main, new RecordingStore(getTargetContext()).entries().get(0));
        testExternalDeletion(main);
    }

    private RecordingStore.Entry historyFixture(RecordingStore store) throws Exception {
        RecordingStore.Output output = store.create();
        output.descriptor.close();
        store.complete(output, 0, 0);
        return store.entries().stream().filter(item -> item.uri.equals(output.uri)).findFirst().get();
    }

    private void deleteOutsideApp(RecordingStore.Entry entry) throws Exception {
        if (SettingsActivity.LOCAL_AUTHORITY.equals(entry.uri.getAuthority())) {
            check(android.provider.DocumentsContract.deleteDocument(getTargetContext().getContentResolver(), entry.uri),
                    "External document deletion failed");
        } else check(getTargetContext().getContentResolver().delete(entry.uri, null, null) == 1,
                "External MediaStore deletion failed");
    }

    private void testExternalDeletion(Activity main) throws Exception {
        final Activity screen = bringMainToFront(main);
        RecordingStore store = new RecordingStore(getTargetContext());
        RecordingStore.Entry stale = historyFixture(store);
        SystemClock.sleep(1200);
        runOnMainSync(() -> {
            android.view.View row = recordingRow(screen, stale.uri.toString());
            check(row != null, "Missing external-deletion fixture row");
            row.findViewById(R.id.recording_delete).performClick();
        });
        // Reproduce a file disappearing after the user opened its Delete confirmation.
        deleteOutsideApp(stale);
        clickSystemText("Delete");
        SystemClock.sleep(1000);
        check(store.entries().stream().noneMatch(item -> item.uri.equals(stale.uri)),
                "Already-missing video cannot be removed through Delete");
        runOnMainSync(() -> check(recordingRow(screen, stale.uri.toString()) == null,
                "Already-missing video's row remains after Delete"));

        RecordingStore.Entry intact = historyFixture(store);
        RecordingStore.Entry removed = historyFixture(store);
        SystemClock.sleep(1200);
        shell("input keyevent KEYCODE_HOME");
        deleteOutsideApp(removed);
        bringMainToFront(screen);
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (store.entries().stream().anyMatch(item -> item.uri.equals(removed.uri))
                && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        check(store.entries().stream().noneMatch(item -> item.uri.equals(removed.uri)),
                "Returning to app retained externally deleted video");
        boolean[] rowRemoved = {false};
        while (!rowRemoved[0] && SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync(() -> rowRemoved[0] = recordingRow(screen, removed.uri.toString()) == null);
            if (!rowRemoved[0]) SystemClock.sleep(100);
        }
        runOnMainSync(() -> {
            check(recordingRow(screen, removed.uri.toString()) == null, "Stale row remains after resume");
            check(recordingRow(screen, intact.uri.toString()) != null, "Existing recording removed during cleanup");
        });
        check(store.availability(intact) == RecordingStore.Availability.PRESENT,
                "Cleanup changed existing recording");
        store.delete(intact);
        append("PASS: " + stale.uri.getAuthority()
                + " external deletion on resume, already-missing Delete, intact-file preservation");
    }

    private android.view.View recordingRow(Activity activity, String key) {
        android.widget.LinearLayout list = visibleActivity(activity).findViewById(R.id.recording_list);
        for (int i = 0; i < list.getChildCount(); i++) {
            android.view.View row = list.getChildAt(i);
            if (key.equals(row.getTag())) return row;
        }
        return null;
    }
    private void testHistoryPreviews(Activity main, RecordingStore.Entry entry) throws Exception {
        main = bringMainToFront(main);
        final Activity screen = main;
        long deadline = SystemClock.elapsedRealtime() + 20000;
        final android.widget.ImageView[] image = {null};
        while (SystemClock.elapsedRealtime() < deadline) {
            runOnMainSync(() -> {
                android.view.View row = recordingRow(screen, entry.uri.toString());
                if (row != null) {
                    android.widget.ImageView preview = row.findViewById(R.id.recording_thumbnail);
                    if (Boolean.TRUE.equals(preview.getTag(R.id.thumbnail_loaded))) image[0] = preview;
                }
            });
            if (image[0] != null) break;
            SystemClock.sleep(100);
        }
        check(image[0] != null, "Local recording thumbnail never loaded: " + entry.uri);
        runOnMainSync(() -> {
            android.widget.TextView footer = visibleActivity(screen).findViewById(R.id.app_version_footer);
            check(footer.getText().toString().contains(BuildConfig.VERSION_NAME)
                    && footer.getText().toString().contains("build " + BuildConfig.VERSION_CODE), "Version footer is stale: " + footer.getText());
            android.view.ViewGroup parent = (android.view.ViewGroup) footer.getParent();
            check(parent.getChildAt(parent.getChildCount() - 1) == footer, "Version is not the final page element");
            image[0].requestRectangleOnScreen(new Rect(0, 0, image[0].getWidth(), image[0].getHeight()), true);
        });
        SystemClock.sleep(600);
        Bitmap screenshot = getUiAutomation().takeScreenshot();
        if (screenshot != null) {
            try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),
                    "history-previews-" + entry.uri.getAuthority() + ".png"))) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, file);
            } finally { screenshot.recycle(); }
        }
        // Cancel before a decode result reaches the UI and make sure it cannot reappear later.
        RecordingThumbnails race = new RecordingThumbnails(getTargetContext());
        final android.widget.ImageView[] cancelledView = {null};
        runOnMainSync(() -> {
            cancelledView[0] = new android.widget.ImageView(getTargetContext());
            race.bind(entry.uri, cancelledView[0]); race.forget(entry.uri); RecordingThumbnails.clear(cancelledView[0]);
            android.view.View row = recordingRow(screen, entry.uri.toString());
            check(row.findViewById(R.id.recording_delete).performClick(), "History delete button unavailable");
        });
        clickSystemText("Delete");
        SystemClock.sleep(1500);
        runOnMainSync(() -> {
            check(recordingRow(screen, entry.uri.toString()) == null, "Deleted recording row/thumbnail remains visible");
            check(image[0].getDrawable() == null && image[0].getTag() == null, "Deleted thumbnail is still bound");
            check(cancelledView[0].getDrawable() == null, "Cancelled preview reappeared after deletion");
            race.close();
        });
        check(new RecordingStore(getTargetContext()).entries().stream().noneMatch(item -> item.uri.equals(entry.uri)), "Deleted item retained in history");
        java.lang.reflect.Field field = MainActivity.class.getDeclaredField("thumbnails"); field.setAccessible(true);
        RecordingThumbnails loader = (RecordingThumbnails) field.get(visibleActivity(screen));
        final android.widget.ImageView[] missing = {null};
        runOnMainSync(() -> { missing[0] = new android.widget.ImageView(getTargetContext()); loader.bind(entry.uri, missing[0]); });
        SystemClock.sleep(1200);
        runOnMainSync(() -> {
            check(!Boolean.TRUE.equals(missing[0].getTag(R.id.thumbnail_loaded)), "Deleted preview remains in memory cache");
            loader.forget(entry.uri); RecordingThumbnails.clear(missing[0]);
        });
        append("PASS: " + entry.uri.getAuthority() + " local thumbnail, final installed-version footer, confirmed UI deletion, cache eviction and cancelled decode race");
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
                    int width = format.getInteger(android.media.MediaFormat.KEY_WIDTH);
                    int height = format.getInteger(android.media.MediaFormat.KEY_HEIGHT);
                    if (android.os.Build.VERSION.SDK_INT == 28) {
                        check(Math.min(width, height) > 0 && Math.min(width, height) <= shortEdge, "Legacy encoder exceeded the selected resolution cap");
                        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                        getTargetContext().getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
                        check(Math.abs((double) width / height - (double) metrics.widthPixels / metrics.heightPixels) < 0.02,
                                "Legacy encoder changed the screen aspect ratio");
                    } else {
                        check(Math.min(width, height) == shortEdge, "Wrong output resolution: " + format);
                    }
                    // Surface capture can emit fewer frames on a static screen or a slow emulator.
                    // The setting requests a target rate; the MP4 reports the achieved average.
                    if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) {
                    float actual = android.os.Build.VERSION.SDK_INT >= 29
                            ? format.getNumber(android.media.MediaFormat.KEY_FRAME_RATE).floatValue()
                            : format.getInteger(android.media.MediaFormat.KEY_FRAME_RATE);
                        check(actual > 0 && actual <= fps + 2, "Output exceeded requested frame rate: " + format);
                    }
                    append("ENCODED: " + format);
                    found = true;
                }
            }
            check(found, "Requested codec missing: " + mime);
        } finally { extractor.release(); }
    }

    private void saveAndroid9Screenshot(String name) {
        if (android.os.Build.VERSION.SDK_INT != 28) return;
        Bitmap preview = getUiAutomation().takeScreenshot();
        if (preview == null) return;
        try (java.io.FileOutputStream file = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), name))) {
            preview.compress(Bitmap.CompressFormat.PNG, 100, file);
        } catch (IOException error) { android.util.Log.w("ScreenOhRecord", "Could not save test screenshot", error); }
        finally { preview.recycle(); }
    }

    void clickSystemText(String wanted) {
        enableInteractiveWindows();
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    if (clickSystemTextInRoot(root, wanted)) { SystemClock.sleep(500); return; }
                } finally { root.recycle(); }
            }
            for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
                root = window.getRoot();
                if (root == null) continue;
                try {
                    if (clickSystemTextInRoot(root, wanted)) { SystemClock.sleep(500); return; }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(200);
        }
        saveAndroid9Screenshot("android9-failure.png");
        throw new AssertionError("System folder-picker action missing: " + wanted);
    }


    private boolean clickSystemTextInRoot(AccessibilityNodeInfo root, String wanted) {
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(wanted)) {
            if (android.os.Build.VERSION.SDK_INT == 28 && "SR9".equals(wanted))
                append("INFO: Android 9 folder candidate text=" + node.getText() + ", description=" + node.getContentDescription());
            boolean matches = wanted.equalsIgnoreCase(String.valueOf(node.getText()))
                    || wanted.equalsIgnoreCase(String.valueOf(node.getContentDescription()));
            if (!node.isVisibleToUser() || !matches) continue;
            AccessibilityNodeInfo target = node;
            for (int level = 0; level < 5 && target != null; level++, target = target.getParent()) {
                if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
            if (android.os.Build.VERSION.SDK_INT == 28) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
                MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
                down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    boolean pressed = getUiAutomation().injectInputEvent(down, true);
                    boolean released = getUiAutomation().injectInputEvent(up, true);
                    if (pressed && released) return true;
                } finally { down.recycle(); up.recycle(); }
            }
        }
        return false;
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

    void notificationAction(String title) throws Exception {
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

    void awaitState(RecordingService.State desired) {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (RecordingService.status().state == desired) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Expected " + desired + ", got " + RecordingService.status().state +
                ": " + RecordingService.status().message);
    }

    void consent(boolean allow) {
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
        if (android.os.Build.VERSION.SDK_INT < 34) return;
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

    void awaitRequestFinished() {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!CaptureActivity.isRequestActive()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Capture-consent host did not finish");
    }

    private void enableInteractiveWindows() {
        AccessibilityServiceInfo serviceInfo = getUiAutomation().getServiceInfo();
        int requiredFlags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        if ((serviceInfo.flags & requiredFlags) != requiredFlags) {
            serviceInfo.flags |= requiredFlags;
            getUiAutomation().setServiceInfo(serviceInfo);
        }
    }

    private AccessibilityNodeInfo overlayNode(String id) {
        enableInteractiveWindows();
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
        long deadline = SystemClock.elapsedRealtime() + 8000;
        do {
            AccessibilityNodeInfo node = overlayNode(id);
            try {
                // Layout updates can invalidate an accessibility node between lookup
                // and dispatch. Retry only rejected clicks, never an accepted action.
                if (node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    SystemClock.sleep(250);
                    return;
                }
            } finally { node.recycle(); }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Floating control click failed: " + id);
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
        if ("input keyevent KEYCODE_HOME".equals(command)) {
            command = "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME";
        }
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[1024];
            int count;
            java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
            while ((count = input.read(buffer)) != -1) response.write(buffer, 0, count);
            if (response.size() > 0) lifecycleTrace.append(command).append('\n').append(response.toString("UTF-8"));
            SystemClock.sleep(300);
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
}

