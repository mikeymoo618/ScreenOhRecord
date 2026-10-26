package com.screenohrecord.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.IOException;
import java.util.List;

/** Live public-release install test. The Android installer may terminate this runner on upgrade. */
public final class UpdateInstallTestRunner extends Instrumentation {
    private boolean install;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments); install = arguments != null && "true".equals(arguments.getString("install")); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!install) {
                if (!UpdateClient.begin()) throw new AssertionError("Update gate unavailable");
                UpdateClient.Release release;
                try { release = UpdateClient.check(getTargetContext(), () -> false); }
                finally { UpdateClient.end(); }
                if (release.versionCode != BuildConfig.VERSION_CODE) throw new AssertionError("Live latest release does not match installed build");
                if (!getTargetContext().getSharedPreferences("updates", 0).getBoolean("upgrade_marker", false))
                    throw new AssertionError("Update did not preserve private settings");
                if (UpdateScheduler.enabled(getTargetContext())) throw new AssertionError("Update enabled weekly checks without approval");
                result.putString("stream", "PASS: live GitHub release metadata matches installed update; private settings preserved and weekly checks remain off\n");
                finish(Activity.RESULT_OK, result); return;
            }
            getTargetContext().getSharedPreferences("updates", 0).edit().putBoolean("upgrade_marker", true).commit();
            Activity activity = startActivitySync(new android.content.Intent(getTargetContext(), UpdatesActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> activity.findViewById(R.id.update_check).performClick());
            waitForText(activity, "An update is available", 60000);
            runOnMainSync(() -> activity.findViewById(R.id.update_download).performClick());
            clickText("Download", 10000);
            // A fresh real GitHub download and verification must precede the system installer.
            long deadline = SystemClock.elapsedRealtime() + 90000;
            boolean confirmed = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                if (root != null) {
                    try {
                        String owner = String.valueOf(root.getPackageName());
                        if (owner.contains("packageinstaller") || owner.contains("permissioncontroller")) {
                            List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId("android:id/button1");
                            for (AccessibilityNodeInfo button : buttons) {
                                if (button.isEnabled() && button.getText() != null
                                        && ("Update".equalsIgnoreCase(button.getText().toString()) || "Install".equalsIgnoreCase(button.getText().toString()))) {
                                    Bundle progress = new Bundle();
                                    progress.putString("stream", "PASS: app downloaded the public GitHub APK, verified it and reached Android's installation confirmation\n");
                                    sendStatus(1, progress);
                                    android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
                                    if (screenshot != null) {
                                        try (java.io.FileOutputStream file = new java.io.FileOutputStream(
                                                new java.io.File(getTargetContext().getExternalFilesDir(null), "update-install-confirmation.png"))) {
                                            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, file);
                                        }
                                        screenshot.recycle();
                                    }
                                    confirmed = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                    if (!confirmed) throw new AssertionError("Android install confirmation was not clickable");
                                    break;
                                }
                            }
                        }
                    } finally { root.recycle(); }
                }
                if (confirmed) break;
                final String[] message = {""};
                runOnMainSync(() -> message[0] = ((TextView) activity.findViewById(R.id.update_status)).getText().toString());
                if (message[0].contains("failed") || message[0].contains("Could not")) throw new AssertionError(message[0]);
                SystemClock.sleep(250);
            }
            if (!confirmed) throw new AssertionError("Android installation confirmation was not reached");
            SystemClock.sleep(15000);
            throw new AssertionError("App process was not replaced after Android install approval; inspect installed version");
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void waitForText(Activity activity, String expected, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            final String[] value = {""};
            runOnMainSync(() -> value[0] = ((TextView) activity.findViewById(R.id.update_status)).getText().toString());
            if (value[0].contains(expected)) return;
            if (value[0].contains("failed")) throw new AssertionError(value[0]);
            SystemClock.sleep(200);
        }
        throw new AssertionError("Updater did not show: " + expected);
    }
    private void clickText(String text, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) try {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                    if (node.getText() != null && text.equalsIgnoreCase(node.getText().toString())
                            && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
                }
            } finally { root.recycle(); }
            SystemClock.sleep(200);
        }
        throw new AssertionError("Update dialog button missing: " + text);
    }
}
