package com.screenohrecord.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;

/** Local controls only. No WebViews, remote content or network APIs. */
public final class MainActivity extends Activity {
    private static final int OVERLAY_PERMISSION = 12;
    private static final int BACKGROUND = Color.rgb(16, 28, 32);
    private static final int SURFACE = Color.rgb(28, 44, 49);
    private static final int INK = Color.rgb(239, 247, 245);
    private static final int MUTED = Color.rgb(164, 187, 185);
    private static final int ACCENT = Color.rgb(99, 230, 195);
    private static final int CORAL = Color.rgb(255, 116, 105);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RecordingStore store;
    private RecordingThumbnails thumbnails;
    private final java.util.concurrent.ExecutorService historyWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private TextView versionFooter;
    private TextView stateLabel;
    private TextView timer;
    private TextView description;
    private Button start;
    private Button pause;
    private Button stop;
    private Button floating;
    private Switch microphone;
    private Button settings;
    private TextView optionsSummary;
    private TextView folderLabel;
    private boolean syncingOptions;
    private Spinner quality;
    private LinearLayout recordings;
    private boolean awaitingOverlayGrant;
    private String renderedHistory = "";

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new RecordingStore(this);
        thumbnails = new RecordingThumbnails(this);
        if (!RecordingService.status().active()) store.recoverInterrupted();
        if (savedInstanceState != null) {
            awaitingOverlayGrant = savedInstanceState.getBoolean("awaitingOverlayGrant");
        }
        buildInterface();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("awaitingOverlayGrant", awaitingOverlayGrant);
        super.onSaveInstanceState(state);
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root = column();
        root.setPadding(dp(24), dp(24), dp(24), dp(32));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(scroll);
        scroll.requestApplyInsets();

        TextView eyebrow = text("YOUR SCREEN. YOUR PHONE.", 11, ACCENT);
        eyebrow.setLetterSpacing(.14f);
        root.addView(eyebrow);
        TextView title = text("ScreenOhRecord", 32, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        marginAdd(root, title, 10);
        TextView subtitle = text("Local recording. Optional GitHub updates.", 16, MUTED);
        marginAdd(root, subtitle, 6);

        TextView privacy = text("NO ADS  ·  NO ANALYTICS  ·  NO RECORDING UPLOADS", 10, ACCENT);
        privacy.setPadding(dp(12), dp(13), dp(12), dp(13));
        privacy.setBackground(background(Color.rgb(22, 57, 49), 10));
        marginAdd(root, privacy, 22);

        LinearLayout card = column();
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(background(SURFACE, 20));
        marginAdd(root, card, 20);
        stateLabel = text("READY TO RECORD", 11, ACCENT);
        stateLabel.setId(R.id.recording_status);
        stateLabel.setLetterSpacing(.12f);
        card.addView(stateLabel);
        timer = text("00:00:00", 42, INK);
        timer.setId(R.id.recording_timer);
        timer.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        marginAdd(card, timer, 10);
        description = text("Android will ask what you want to capture.", 14, MUTED);
        marginAdd(card, description, 8);

        start = button("Start recording", ACCENT, BACKGROUND);
        start.setId(R.id.start_recording);
        start.setOnClickListener(view -> requestRecording());
        marginAdd(card, start, 20);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        pause = button("Pause", Color.rgb(50, 69, 73), INK);
        pause.setId(R.id.pause_recording);
        pause.setOnClickListener(view -> sendCommand(RecordingService.status().state ==
                RecordingService.State.PAUSED ? RecordingService.RESUME : RecordingService.PAUSE));
        stop = button("Stop & save", CORAL, BACKGROUND);
        stop.setId(R.id.stop_recording);
        stop.setOnClickListener(view -> sendCommand(RecordingService.STOP));
        controls.addView(pause, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams stopLayout = new LinearLayout.LayoutParams(0, dp(52), 1);
        stopLayout.setMarginStart(dp(10));
        controls.addView(stop, stopLayout);
        marginAdd(card, controls, 10);

        floating = button("Show floating controls", Color.rgb(50, 69, 73), INK);
        floating.setId(R.id.floating_toggle);
        floating.setOnClickListener(view -> toggleFloating());
        marginAdd(root, floating, 18);
        marginAdd(root, text("Optional draggable bubble with start, pause and stop. Android’s Display over other apps permission is required.", 12, MUTED), 8);

        TextView options = text("RECORDING OPTIONS", 11, MUTED);
        options.setLetterSpacing(.12f);
        marginAdd(root, options, 28);
        TextView qualityLabel = text("Video quality", 15, INK);
        marginAdd(root, qualityLabel, 14);
        quality = new Spinner(this);
        quality.setId(R.id.recording_quality);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"720p · smaller files", "1080p · more detail"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        quality.setAdapter(adapter);
        SharedPreferences optionsSaved = RecordingOptions.get(this);
        quality.setSelection(optionsSaved.getInt("quality", 720) >= 1080 ? 1 : 0);
        marginAdd(root, quality, 4);
        microphone = new Switch(this);
        microphone.setId(R.id.microphone);
        microphone.setText(R.string.microphone_audio);
        microphone.setTextSize(16);
        microphone.setTextColor(INK);
        microphone.setChecked(optionsSaved.getBoolean("microphone", true));
        microphone.setPadding(0, dp(16), 0, dp(12));
        marginAdd(root, microphone, 8);
        microphone.setOnCheckedChangeListener((button, checked) -> {
            if (!syncingOptions) RecordingOptions.get(this).edit().putBoolean("microphone", checked).apply();
            refreshOptionsSummary();
        });
        quality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int selected, long id) {
                if (!syncingOptions) RecordingOptions.get(MainActivity.this).edit()
                        .putInt("quality", selected == 1 ? 1080 : 720).apply();
                refreshOptionsSummary();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        marginAdd(root, text("Microphone is on by default. Other apps’ internal audio is not captured.", 12, MUTED), 0);
        settings = button("Settings", Color.rgb(50, 69, 73), INK);
        settings.setId(R.id.recording_settings);
        settings.setOnClickListener(v -> {
            saveOptions(); startActivity(new Intent(this, SettingsActivity.class));
        });
        marginAdd(root, settings, 12);
        optionsSummary = text(RecordingOptions.summary(this), 13, MUTED);
        marginAdd(root, optionsSummary, 8);
        Button updates = button("App updates", Color.rgb(50, 69, 73), INK);
        updates.setId(R.id.open_updates);
        updates.setOnClickListener(v -> startActivity(new Intent(this, UpdatesActivity.class)));
        marginAdd(root, updates, 8);
        marginAdd(root, text("While recording other apps, use the floating bubble or notification to pause or stop. " +
                "Turning the screen off stops recording. Keep the phone’s orientation steady.", 13, MUTED), 20);
        marginAdd(root, text("Whole-screen capture can include private messages and notifications. " +
                "Android asks for consent before each entire-screen recording.", 13, MUTED), 10);

        TextView recent = text("SAVED ON THIS PHONE", 11, ACCENT);
        recent.setLetterSpacing(.12f);
        marginAdd(root, recent, 30);
        folderLabel = text(RecordingOptions.folder(this), 13, MUTED);
        marginAdd(root, folderLabel, 7);
        recordings = column();
        recordings.setId(R.id.recording_list);
        marginAdd(root, recordings, 14);
        marginAdd(root, text("Local MP4 \u00b7 No livestreaming\n" +
                "Other gallery apps may back up files if you enable their cloud sync.", 11, MUTED), 25);
        versionFooter = text("", 12, MUTED);
        versionFooter.setId(R.id.app_version_footer);
        versionFooter.setGravity(Gravity.CENTER);
        marginAdd(root, versionFooter, 18);
        refreshVersionFooter();
    }

    private void requestRecording() {
        saveOptions();
        CaptureActivity.launch(this, microphone.isChecked(), quality.getSelectedItemPosition() == 1 ? 1080 : 720);
        refreshStatus();
    }

    private void saveOptions() {
        if (microphone == null || quality == null) return;
        RecordingOptions.get(this).edit()
                .putBoolean("microphone", microphone.isChecked())
                .putInt("quality", quality.getSelectedItemPosition() == 1 ? 1080 : 720).apply();
    }

    private void toggleFloating() {
        saveOptions();
        if (FloatingControlsService.isVisible()) {
            stopService(new Intent(this, FloatingControlsService.class));
            return;
        }
        if (Settings.canDrawOverlays(this)) { showFloating(); return; }
        awaitingOverlayGrant = true;
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), OVERLAY_PERMISSION);
        } catch (RuntimeException error) {
            awaitingOverlayGrant = false;
            toast("Open Android app settings and enable Display over other apps for ScreenOhRecord.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION) completeOverlayRequest();
    }

    private void completeOverlayRequest() {
        if (!awaitingOverlayGrant) return;
        awaitingOverlayGrant = false;
        if (Settings.canDrawOverlays(this)) showFloating();
        else toast("Floating controls were not enabled. App and notification controls are still available.");
    }

    private void showFloating() {
        try {
            startForegroundService(new Intent(this, FloatingControlsService.class).setAction(FloatingControlsService.SHOW));
        } catch (RuntimeException error) {
            toast("Android could not show the floating controls. Try again with ScreenOhRecord open.");
        }
    }

    private void sendCommand(String action) {
        if (!RecordingService.status().active()) return;
        startService(new Intent(this, RecordingService.class).setAction(action));
    }

    private void refreshStatus() {
        if (stateLabel == null) return;
        RecordingService.Status current = RecordingService.status();
        boolean waiting = CaptureActivity.isRequestActive();
        settings.setEnabled(!current.active() && !waiting);
        setTextIfChanged(stateLabel, current.state == RecordingService.State.IDLE ? "READY TO RECORD" : current.state.name());
        stateLabel.setTextColor(current.state == RecordingService.State.RECORDING ? CORAL : ACCENT);
        setTextIfChanged(timer, RecordingConfig.elapsed(current.duration));
        setTextIfChanged(description, waiting ? "Waiting for Android’s capture permission…" : current.message);
        start.setEnabled(!current.active() && !waiting);
        start.setVisibility(current.active() ? View.GONE : View.VISIBLE);
        boolean controllable = current.state == RecordingService.State.RECORDING ||
                current.state == RecordingService.State.PAUSED;
        pause.setEnabled(controllable);
        stop.setEnabled(controllable);
        setTextIfChanged(pause, current.state == RecordingService.State.PAUSED ? "Resume" : "Pause");
        microphone.setEnabled(!current.active() && !waiting);
        quality.setEnabled(!current.active() && !waiting);
        setTextIfChanged(floating, FloatingControlsService.isVisible() ? "Hide floating controls" : "Show floating controls");
        refreshRecordings();
    }

    private void refreshRecordings() {
        List<RecordingStore.Entry> entries = store.entries();
        StringBuilder fingerprint = new StringBuilder();
        for (RecordingStore.Entry entry : entries) fingerprint.append(entry.uri).append(entry.bytes);
        if (renderedHistory.equals(fingerprint.toString()) && recordings.getChildCount() > 0) return;
        renderedHistory = fingerprint.toString();
        thumbnails.cancelPending();
        for (int i = 0; i < recordings.getChildCount(); i++) {
            ImageView oldPreview = recordings.getChildAt(i).findViewById(R.id.recording_thumbnail);
            if (oldPreview != null) RecordingThumbnails.clear(oldPreview);
        }
        recordings.removeAllViews();
        if (entries.isEmpty()) {
            recordings.addView(text("Your recordings will appear here.\nNo account or cloud upload involved.", 14, MUTED));
            return;
        }
        for (RecordingStore.Entry entry : entries) {
            LinearLayout row = column();
            row.setTag(entry.uri.toString());
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setBackground(background(SURFACE, 12));
            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            ImageView preview = new ImageView(this);
            preview.setId(R.id.recording_thumbnail);
            preview.setContentDescription("Preview of " + entry.name);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setBackground(background(Color.rgb(50, 69, 73), 8));
            preview.setClipToOutline(true);
            heading.addView(preview, new LinearLayout.LayoutParams(dp(96), dp(64)));
            LinearLayout metadata = column();
            LinearLayout.LayoutParams metadataLayout = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            metadataLayout.setMarginStart(dp(12));
            heading.addView(metadata, metadataLayout);
            TextView name = text(entry.name, 13, INK);
            name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            metadata.addView(name);
            marginAdd(metadata, text(RecordingConfig.elapsed(entry.duration) + " \u00b7 " +
                    String.format(Locale.ROOT, "%.1f MB", entry.bytes / 1_000_000d), 12, MUTED), 5);
            row.addView(heading);
            thumbnails.bind(entry.uri, preview);
            LinearLayout actions = new LinearLayout(this);
            Button open = button("Play", Color.rgb(50, 69, 73), INK);
            open.setId(R.id.recording_play);
            open.setOnClickListener(view -> open(entry));
            actions.addView(open, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button remove = button("Delete", Color.TRANSPARENT, CORAL);
            remove.setId(R.id.recording_delete);
            remove.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("Delete recording?").setMessage(entry.name)
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Delete", (dialog, which) -> {
                        try {
                            store.delete(entry);
                            thumbnails.forget(entry.uri);
                            RecordingThumbnails.clear(preview);
                            refreshRecordings();
                        } catch (RuntimeException error) { toast("Could not delete video. It may have been moved."); }
                    }).show());
            actions.addView(remove, new LinearLayout.LayoutParams(0, dp(44), 1));
            marginAdd(row, actions, 10);
            marginAdd(recordings, row, 8);
        }
    }
    private void open(RecordingStore.Entry entry) {
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(entry.uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContentResolver(), "Local screen recording", entry.uri));
        try { startActivity(Intent.createChooser(intent, "Play local recording")); }
        catch (ActivityNotFoundException error) { toast("No video player is installed."); }
        catch (SecurityException error) { toast("The recording is no longer available at its saved location."); }
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private Button button(String label, int background, int color) {
        Button view = new Button(this);
        view.setText(label); view.setAllCaps(false); view.setTextSize(15);
        view.setTextColor(color); view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        view.setBackground(background(background, 12));
        return view;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        return shape;
    }

    private void marginAdd(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void setTextIfChanged(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override protected void onResume() {
        super.onResume();
        refreshVersionFooter();
        syncingOptions = true;
        SharedPreferences options = RecordingOptions.get(this);
        microphone.setChecked(options.getBoolean("microphone", true));
        quality.setSelection(options.getInt("quality", 720) >= 1080 ? 1 : 0);
        syncingOptions = false;
        refreshOptionsSummary();
        completeOverlayRequest();
        historyWorker.execute(() -> {
            List<Uri> removed = store.pruneMissing();
            handler.post(() -> {
                if (isDestroyed()) return;
                for (Uri uri : removed) thumbnails.forget(uri);
                refreshRecordings();
            });
        });
        handler.removeCallbacks(refresh); handler.post(refresh);
    }
    @Override protected void onPause() {
        handler.removeCallbacks(refresh); super.onPause();
    }
    private void refreshOptionsSummary() {
        if (optionsSummary != null) optionsSummary.setText(RecordingOptions.summary(this));
        if (folderLabel != null) folderLabel.setText(RecordingOptions.folder(this));
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(refresh);
        if (thumbnails != null) thumbnails.close();
        historyWorker.shutdownNow();
        super.onDestroy();
    }
    private void refreshVersionFooter() {
        if (versionFooter == null) return;
        String name = BuildConfig.VERSION_NAME;
        long code = BuildConfig.VERSION_CODE;
        try {
            android.content.pm.PackageInfo installed = getPackageManager().getPackageInfo(getPackageName(), 0);
            name = installed.versionName; code = installed.getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        versionFooter.setText("ScreenOhRecord " + name + " · build " + code);
    }
}
