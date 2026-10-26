package com.screenohrecord.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

/** User-triggered, non-exported consent host. Never stores a projection token. */
public final class CaptureActivity extends Activity {
    private static final int PERMISSIONS = 10;
    private static final int CAPTURE = 11;
    private static volatile boolean requestActive;
    private static final CopyOnWriteArraySet<Runnable> requestListeners = new CopyOnWriteArraySet<>();
    private boolean microphone;
    private int quality;
    private int fps;
    private int bitrate;
    private String codec;
    private boolean submitted;

    static boolean isRequestActive() { return requestActive; }
    static void addRequestListener(Runnable listener) { requestListeners.add(listener); }
    static void removeRequestListener(Runnable listener) { requestListeners.remove(listener); }
    private static void notifyRequests() { for (Runnable listener : requestListeners) listener.run(); }

    static void launch(Context context, boolean microphone, int quality) {
        if (requestActive || RecordingService.status().active()) return;
        if (UpdateClient.isBusy()) {
            Toast.makeText(context, "Wait for the update check or download to finish before recording.", Toast.LENGTH_LONG).show();
            return;
        }
        requestActive = true;
        Intent intent = new Intent(context, CaptureActivity.class)
                .putExtra("microphone", microphone).putExtra("quality", quality)
                .putExtra("fps", RecordingOptions.get(context).getInt("fps", 15))
                .putExtra("bitrate", RecordingOptions.get(context).getInt("bitrate", 1_000_000))
                .putExtra("codec", RecordingOptions.codec(context));
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            // Keep the user-tapped overlay visible until Android accepts the activity launch.
            context.startActivity(intent);
            notifyRequests();
        } catch (RuntimeException error) {
            requestActive = false;
            notifyRequests();
            Toast.makeText(context, "Open ScreenOhRecord to request screen-capture permission.", Toast.LENGTH_LONG).show();
        }
    }

    private final RecordingService.StatusListener recordingListener = current -> {
        if (submitted && (current.state == RecordingService.State.RECORDING || current.state == RecordingService.State.IDLE)) {
            if (current.state == RecordingService.State.IDLE) toast(current.message);
            finish();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new android.widget.FrameLayout(this));
        requestActive = true;
        notifyRequests();
        RecordingService.addStatusListener(recordingListener);
        if (state != null) {
            microphone = state.getBoolean("microphone");
            quality = state.getInt("quality", 720);
            fps = state.getInt("fps", 15);
            bitrate = state.getInt("bitrate", 1_000_000);
            codec = RecordingOptions.validCodec(state.getString("codec"));
            submitted = state.getBoolean("submitted");
            if (submitted && (RecordingService.status().state == RecordingService.State.RECORDING
                    || RecordingService.status().state == RecordingService.State.IDLE)) finish();
            return;
        }
        microphone = getIntent().getBooleanExtra("microphone", true);
        fps = getIntent().getIntExtra("fps", 15);
        bitrate = getIntent().getIntExtra("bitrate", 1_000_000);
        codec = RecordingOptions.validCodec(getIntent().getStringExtra("codec"));
        quality = getIntent().getIntExtra("quality", 720);
        if (RecordingService.status().active()) { finish(); return; }
        List<String> permissions = new ArrayList<>();
        if (microphone && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (permissions.isEmpty()) requestScreen();
        else requestPermissions(permissions.toArray(new String[0]), PERMISSIONS);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != PERMISSIONS) return;
        if (microphone && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Microphone permission denied. Turn microphone audio off to record silently.");
            finish();
            return;
        }
        requestScreen();
    }

    private void requestScreen() {
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent capture = Build.VERSION.SDK_INT >= 34
                    ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            startActivityForResult(capture, CAPTURE);
        } catch (RuntimeException error) {
            toast("Android cannot start screen sharing on this device.");
            finish();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != CAPTURE) return;
        if (result != RESULT_OK || data == null) { finish(); return; }
        submitted = true;
        try {
            startForegroundService(new Intent(this, RecordingService.class).setAction(RecordingService.START)
                    .putExtra("resultCode", result).putExtra("consent", data)
                    .putExtra("microphone", microphone).putExtra("quality", quality)
                    .putExtra("fps", fps).putExtra("bitrate", bitrate).putExtra("codec", codec));
            // Stay visible until the recorder enters its foreground session, including microphone access.
        } catch (RuntimeException error) {
            toast("Android did not allow the recording service to start.");
            finish();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("microphone", microphone);
        state.putInt("quality", quality);
        state.putInt("fps", fps);
        state.putInt("bitrate", bitrate);
        state.putString("codec", codec);
        state.putBoolean("submitted", submitted);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        RecordingService.removeStatusListener(recordingListener);
        if (!isChangingConfigurations()) { requestActive = false; notifyRequests(); }
        super.onDestroy();
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
