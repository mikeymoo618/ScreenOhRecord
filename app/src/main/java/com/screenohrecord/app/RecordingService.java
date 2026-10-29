package com.screenohrecord.app;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaCodecList;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArraySet;

/** One local recording session; projection tokens are never persisted or reused. */
public final class RecordingService extends Service {
    static final String START = "com.screenohrecord.app.START";
    static final String PAUSE = "com.screenohrecord.app.PAUSE";
    static final String RESUME = "com.screenohrecord.app.RESUME";
    static final String STOP = "com.screenohrecord.app.STOP";
    static final String DISCARD = "com.screenohrecord.app.DISCARD";
    private static final String CHANNEL = "screenohrecord_capture";
    private static final int NOTIFICATION_ID = 11;

    enum State { IDLE, PREPARING, RECORDING, PAUSED, STOPPING }

    static final class Status {
        final State state;
        final long duration;
        final String message;
        final boolean discardSucceeded;
        Status(State state, long duration, String message) {
            this(state, duration, message, false);
        }
        Status(State state, long duration, String message, boolean discardSucceeded) {
            this.state = state; this.duration = duration; this.message = message;
            this.discardSucceeded = discardSucceeded;
        }
        boolean active() { return state != State.IDLE; }
    }

    private static volatile Status status = new Status(State.IDLE, 0, "Ready to record");
    interface StatusListener { void onStatusChanged(Status current); }
    private static final CopyOnWriteArraySet<StatusListener> listeners = new CopyOnWriteArraySet<>();
    static void addStatusListener(StatusListener listener) { listeners.add(listener); }
    static void removeStatusListener(StatusListener listener) { listeners.remove(listener); }
    private static void updateStatus(Status next) {
        status = next;
        for (StatusListener listener : listeners) listener.onStatusChanged(next);
    }
    private static volatile long segmentStarted;
    static Status status() {
        Status current = status;
        if (current.state == State.RECORDING) {
            return new Status(current.state, current.duration +
                    Math.max(0, SystemClock.elapsedRealtime() - segmentStarted), current.message);
        }
        return current;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private RecordingStore store;
    private RecordingStore.Output output;
    private boolean recorderStarted;
    private boolean stopping;
    private boolean screenReceiverRegistered;
    private boolean microphone;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { finish("Screen sharing stopped", true); }
    };
    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                finish("Recording stopped when the screen turned off", true);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new RecordingStore(this);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_description));
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (START.equals(action)) {
            if (!status.active()) startRecording(intent);
        } else if (PAUSE.equals(action)) {
            pause();
        } else if (RESUME.equals(action)) {
            resume();
        } else if (STOP.equals(action)) {
            finish("Recording saved on this phone", true);
        } else if (DISCARD.equals(action)) {
            finish("Recording permanently deleted", false);
        }
        if (!status.active()) stopSelf();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startRecording(Intent intent) {
        stopping = false;
        updateStatus(new Status(State.PREPARING, 0, "Preparing local recording"));
        microphone = intent.getBooleanExtra("microphone", false);
        try {
            if (microphone && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Microphone permission was not granted");
            }
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 29) {
                int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (microphone && Build.VERSION.SDK_INT >= 30) {
                    types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIFICATION_ID, notification, types);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent consent = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra("consent", Intent.class)
                    : intent.getParcelableExtra("consent");
            if (resultCode != Activity.RESULT_OK || consent == null) {
                throw new SecurityException("Android screen-sharing permission was not granted");
            }
            projection = getSystemService(MediaProjectionManager.class)
                    .getMediaProjection(resultCode, consent);
            if (projection == null) throw new IOException("Screen sharing is unavailable");
            projection.registerCallback(projectionCallback, handler);

            DisplayMetrics metrics = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
            int quality = intent.getIntExtra("quality", 720) >= 1080 ? 1080 : 720;
            int[] size = RecordingConfig.chooseSize(metrics.widthPixels, metrics.heightPixels, quality);
            int fps = RecordingOptions.fps(intent.getIntExtra("fps", 15));
            int bitrate = RecordingOptions.bitrate(intent.getIntExtra("bitrate", 1_000_000));
            String codec = RecordingOptions.validCodec(intent.getStringExtra("codec"));
            if ("av1".equals(codec) && Build.VERSION.SDK_INT < 33)
                throw new IOException("AV1 recording requires Android 13 or newer. Choose H.264 or HEVC.");
            MediaFormat format = MediaFormat.createVideoFormat(RecordingOptions.mime(codec), size[0], size[1]);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            if (new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) == null)
                throw new IOException("Unsupported recording settings. Try H.264, 720p and 15 fps in Settings");
            output = store.create();
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            if (microphone) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(output.descriptor.getFileDescriptor());
            recorder.setVideoEncoder("av1".equals(codec) ? MediaRecorder.VideoEncoder.AV1
                    : "hevc".equals(codec) ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(size[0], size[1]);
            recorder.setVideoFrameRate(fps);
            recorder.setVideoEncodingBitRate(bitrate);
            if (microphone) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioEncodingBitRate(96_000);
                recorder.setAudioSamplingRate(44_100);
            }
            recorder.setOnErrorListener((mediaRecorder, what, extra) ->
                    finish("The encoder stopped unexpectedly; any incomplete video was discarded", false));
            recorder.prepare();
            display = projection.createVirtualDisplay("ScreenOhRecord-local", size[0], size[1],
                    metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(), null, handler);
            if (display == null) throw new IOException("Cannot create the screen capture display");
            recorder.start();
            recorderStarted = true;
            segmentStarted = SystemClock.elapsedRealtime();
            updateStatus(new Status(State.RECORDING, 0,
                    microphone ? "Recording with microphone" : "Recording without audio"));
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            }
            screenReceiverRegistered = true;
            updateNotification();
        } catch (IOException | RuntimeException error) {
            android.util.Log.w("ScreenOhRecord", "Local recording could not start", error);
            finish("Could not start recording: " + safeMessage(error), false);
        }
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private void pause() {
        if (status.state != State.RECORDING || recorder == null) return;
        try {
            long elapsed = status().duration;
            recorder.pause();
            updateStatus(new Status(State.PAUSED, elapsed, "Paused — nothing is being added to the video"));
            updateNotification();
        } catch (RuntimeException error) {
            finish("Could not pause the encoder", true);
        }
    }

    private void resume() {
        if (status.state != State.PAUSED || recorder == null) return;
        try {
            recorder.resume();
            segmentStarted = SystemClock.elapsedRealtime();
            updateStatus(new Status(State.RECORDING, status.duration,
                    microphone ? "Recording with microphone" : "Recording without audio"));
            updateNotification();
        } catch (RuntimeException error) {
            finish("Could not resume the encoder", true);
        }
    }

    private void finish(String message, boolean save) {
        if (stopping) return;
        if (!status.active() && recorder == null && projection == null) {
            stopSelf();
            return;
        }
        stopping = true;
        long duration = status().duration;
        updateStatus(new Status(State.STOPPING, duration, "Finishing the local file"));
        boolean usable = false;
        boolean discardSucceeded = false;
        long bytes = 0;
        if (recorder != null) {
            try {
                if (recorderStarted) {
                    recorder.stop();
                    usable = save;
                }
            } catch (RuntimeException error) {
                if (save) message = "Recording was too short or interrupted; incomplete file discarded";
            } finally {
                recorderStarted = false;
                try { recorder.release(); } catch (RuntimeException ignored) { }
                recorder = null;
            }
        }
        if (display != null) {
            display.release();
            display = null;
        }
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        if (screenReceiverRegistered) {
            unregisterReceiver(screenOffReceiver);
            screenReceiverRegistered = false;
        }
        if (output != null) {
            try { bytes = output.descriptor.getStatSize(); }
            catch (RuntimeException ignored) { }
            try { output.descriptor.close(); }
            catch (IOException error) { usable = false; message = "Could not close the local video file"; }
            if (usable && bytes > 0) {
                try {
                    store.complete(output, bytes, duration);
                } catch (IOException | RuntimeException error) {
                    // Keep a successfully stopped recording; do not erase it for a history error.
                    message = "Video stopped, but storage needs checking: " + safeMessage(error);
                }
            } else {
                discardSucceeded = store.discard(output.uri);
                if (!discardSucceeded)
                    message = "Could not delete the recording. Restore folder access and reopen the app to retry cleanup.";
                if (save && bytes <= 0) message = "No video frames were saved; incomplete file discarded";
            }
            output = null;
        }
        updateStatus(new Status(State.IDLE, duration, message, discardSucceeded));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private PendingIntent command(String action, int request) {
        Intent intent = new Intent(this, RecordingService.class).setAction(action);
        return PendingIntent.getService(this, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_record)
                .setContentTitle("ScreenOhRecord")
                .setContentText(status.message + " · saved locally")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (status.state == State.RECORDING || status.state == State.PAUSED) {
            boolean paused = status.state == State.PAUSED;
            builder.addAction(new Notification.Action.Builder(null, paused ? "Resume" : "Pause",
                    command(paused ? RESUME : PAUSE, 1)).build());
        }
        builder.addAction(new Notification.Action.Builder(null, "Stop", command(STOP, 2)).build());
        return builder.build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    @Override public void onDestroy() {
        if (status.active()) finish("Recording service stopped", true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
