package com.screenohrecord.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit public-release updater; APK installation always requests Android consent. */
public final class UpdatesActivity extends Activity {
    private static final String INSTALL_RESULT = "com.screenohrecord.app.INSTALL_RESULT";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private TextView status, details;
    private Button check, download;
    private UpdateClient.Release release;
    private boolean working, awaitingPermission, installing;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        awaitingPermission = state != null && state.getBoolean("awaiting_install_permission");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding); scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
        Button done = button(root, "Done"); done.setOnClickListener(v -> finish());
        text(root, "ScreenOhRecord updates", 25);
        text(root, "Installed: " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")", 16);
        text(root, "Updates come from mikeymoo618/ScreenOhRecord on GitHub. Recording stays local. No ads, analytics or uploads.", 15);
        status = text(root, getSharedPreferences("updates", MODE_PRIVATE).getString("last_result", "No update check has been made."), 17);
        status.setId(R.id.update_status);
        details = text(root, "", 15); details.setId(R.id.update_details);
        check = button(root, "Check for updates"); check.setId(R.id.update_check);
        check.setOnClickListener(v -> checkNow());
        download = button(root, "Download and install update"); download.setId(R.id.update_download);
        download.setOnClickListener(v -> confirmDownload());
        Switch weekly = new Switch(this); weekly.setId(R.id.update_weekly);
        weekly.setText("Check weekly over Wi-Fi"); weekly.setChecked(UpdateScheduler.enabled(this));
        weekly.setOnCheckedChangeListener((view, enabled) -> {
            if (!UpdateScheduler.setEnabled(this, enabled)) {
                view.setChecked(false); status.setText("Android could not schedule the weekly check.");
            }
        }); root.addView(weekly);
        text(root, "Off until you enable it. Weekly checks use unmetered Wi-Fi and fetch only small release information; Android may delay them. APKs are downloaded only after you approve. No update requests run while recording.", 14);
        text(root, "The APK must match this app's package and signing certificate and pass its size and SHA-256 checks. Android still asks you to allow installation. Downloads may use mobile data when requested manually.", 14);
        release = UpdateClient.cached(this); refresh(); handleInstallResult(getIntent());
    }
    private TextView text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setPadding(0, 18, 0, 18); root.addView(view); return view;
    }
    private Button button(LinearLayout root, String value) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false);
        root.addView(button); return button;
    }
    private void refresh() {
        check.setEnabled(!working && !installing);
        download.setEnabled(!working && !installing && release != null && release.versionCode > BuildConfig.VERSION_CODE);
        if (release != null) details.setText("Latest published: " + release.versionName + " (build " + release.versionCode + ")\n"
                + String.format(Locale.ROOT, "APK download: %.2f MB\n\n", release.size / 1_000_000d) + release.notes);
    }
    private boolean begin() {
        if (working || installing || !UpdateClient.begin()) {
            status.setText("Stop recording or wait for the current update operation before checking."); return false;
        }
        working = true; refresh(); return true;
    }
    private void checkNow() {
        if (!begin()) return;
        status.setText("Checking GitHub release information...");
        new Thread(() -> {
            UpdateClient.Release found = null; String message;
            try {
                found = UpdateClient.check(getApplicationContext(), cancelled::get);
                message = found.versionCode > BuildConfig.VERSION_CODE ? "An update is available. Review the notes before downloading."
                        : "You have the latest published version.";
            } catch (Exception error) { message = "Check failed: " + safeMessage(error); }
            finally { UpdateClient.end(); }
            UpdateClient.Release result = found; String resultMessage = message;
            handler.post(() -> { working = false; if (!isDestroyed()) { release = result; status.setText(resultMessage); refresh(); } });
        }, "ScreenOhRecord-check-update").start();
    }
    private void confirmDownload() {
        if (release == null || release.versionCode <= BuildConfig.VERSION_CODE) return;
        UpdateClient.Release selected = release;
        new AlertDialog.Builder(this).setTitle("Upgrade to " + selected.versionName + "?")
                .setMessage(String.format(Locale.ROOT, "Download %.2f MB from GitHub? This may use mobile data. Android will ask for installation confirmation.", selected.size / 1_000_000d))
                .setNegativeButton("Cancel", null).setPositiveButton("Download", (dialog, which) -> download(selected)).show();
    }
    private void download(UpdateClient.Release selected) {
        if (!begin()) return;
        status.setText("Downloading and verifying the signed APK...");
        new Thread(() -> {
            File downloaded = null; String failure = null;
            try { downloaded = UpdateClient.download(getApplicationContext(), selected, cancelled::get); }
            catch (Exception error) { failure = "Update download failed: " + safeMessage(error); }
            finally { UpdateClient.end(); }
            File result = downloaded; String errorMessage = failure;
            handler.post(() -> {
                working = false;
                if (isDestroyed()) return;
                if (result == null) { status.setText(errorMessage); refresh(); }
                else { release = selected; requestInstall(); }
            });
        }, "ScreenOhRecord-download-update").start();
    }
    private void requestInstall() {
        if (RecordingService.status().active() || CaptureActivity.isRequestActive()) {
            status.setText("Stop recording before installing an update."); refresh(); return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            awaitingPermission = true;
            status.setText("Allow ScreenOhRecord to install its updates, then return here.");
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException error) { awaitingPermission = false; status.setText("Open Android settings and allow installs from ScreenOhRecord."); }
            refresh(); return;
        }
        if (!UpdateClient.begin()) { status.setText("Wait for the current update operation before installing."); refresh(); return; }
        installing = true; refresh(); status.setText("Verifying APK before opening Android's installer...");
        UpdateClient.Release selected = release;
        new Thread(() -> {
            PackageInstaller installer = getPackageManager().getPackageInstaller(); int id = -1;
            try {
                File apk = new File(getFilesDir(), "updates/verified.apk");
                if (selected == null) throw new java.io.IOException("Check for an update again");
                UpdateClient.verify(this, apk, selected, true);
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(getPackageName()); params.setSize(apk.length());
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream input = new FileInputStream(apk); OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        session.fsync(output);
                    }
                    Intent result = new Intent(this, UpdatesActivity.class).setAction(INSTALL_RESULT)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    Bundle options = null;
                    if (Build.VERSION.SDK_INT >= 35) options = ActivityOptions.makeBasic()
                            .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
                    PendingIntent callback = PendingIntent.getActivity(this, 121, result, flags, options);
                    session.commit(callback.getIntentSender());
                }
            } catch (Exception error) {
                UpdateClient.end();
                if (id >= 0) try { installer.abandonSession(id); } catch (RuntimeException ignored) { }
                handler.post(() -> { installing = false; if (!isDestroyed()) { status.setText("Could not install update: " + safeMessage(error)); refresh(); } });
            }
        }, "ScreenOhRecord-install-update").start();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleInstallResult(intent); }
    private void handleInstallResult(Intent intent) {
        if (!INSTALL_RESULT.equals(intent.getAction())) return;
        int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) { startActivity(confirmation); return; }
        }
        installing = false; UpdateClient.end();
        if (result == PackageInstaller.STATUS_SUCCESS) {
            status.setText("Update installed. Your recordings and settings were kept.");
            File apk = new File(getFilesDir(), "updates/verified.apk");
            if (apk.exists() && !apk.delete()) android.util.Log.w("ScreenOhRecord", "Could not remove installed APK cache");
        } else status.setText("Installation was cancelled or could not finish. You can try again.");
        setIntent(new Intent(this, UpdatesActivity.class)); refresh();
    }
    @Override protected void onResume() {
        super.onResume();
        if (awaitingPermission) {
            awaitingPermission = false;
            if (getPackageManager().canRequestPackageInstalls()) requestInstall();
            else status.setText("Installation permission was not enabled. Tap Download and install to try again.");
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("awaiting_install_permission", awaitingPermission); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { cancelled.set(true); super.onDestroy(); }
    private static String safeMessage(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}
