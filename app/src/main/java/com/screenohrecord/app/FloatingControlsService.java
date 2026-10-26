package com.screenohrecord.app;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Explicitly enabled local controls. Idle state does not poll, record or access the network. */
public final class FloatingControlsService extends Service {
    static final String SHOW = "com.screenohrecord.app.SHOW_FLOATING";
    static final String HIDE = "com.screenohrecord.app.HIDE_FLOATING";
    private static final String CHANNEL = "screenohrecord_floating";
    private static final int NOTIFICATION = 12;
    private static volatile boolean visible;
    static boolean isVisible() { return visible; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager manager;
    private WindowManager.LayoutParams position;
    private LinearLayout root;
    private LinearLayout panel;
    private TextView bubble;
    private TextView label;
    private Button start;
    private Button pause;
    private Button stop;
    private Context windowContext;
    private boolean expanded;
    private boolean closeAfterDiscard;
    private boolean dragging;
    private boolean rightEdge = true;
    private int appliedX = Integer.MIN_VALUE, appliedY = Integer.MIN_VALUE, appliedWidth, appliedHeight;
    private AppOpsManager appOps;
    private final RecordingService.StatusListener recordingListener = current -> refresh();
    private final Runnable requestListener = this::refresh;
    private final AppOpsManager.OnOpChangedListener permissionListener = (op, packageName) -> handler.post(() -> {
        if (!Settings.canDrawOverlays(this)) stopSelf();
    });

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Floating recording controls",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.setDescription("User-enabled start, pause and stop bubble. Hide it to close these controls.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        RecordingService.addStatusListener(recordingListener);
        CaptureActivity.addRequestListener(requestListener);
        appOps = getSystemService(AppOpsManager.class);
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, getPackageName(), permissionListener);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || HIDE.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!SHOW.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps in Android settings to show the bubble.", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, notification);
        }
        try {
            if (root == null) createBubble();
            refresh();
        } catch (RuntimeException error) {
            android.util.Log.w("ScreenOhRecord", "Could not initialize optional floating controls", error);
            Toast.makeText(this, "Android could not display the floating controls. Notification controls remain available.", Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 20, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent hide = PendingIntent.getService(this, 21,
                new Intent(this, FloatingControlsService.class).setAction(HIDE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_record)
                .setContentTitle("ScreenOhRecord floating controls")
                .setContentText("Tap the bubble to record. No recording starts automatically.")
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Hide bubble", hide).build()).build();
    }

    private void createBubble() {
        Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("No display is available for floating controls");
        Context displayContext = createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= 30) {
            displayContext = displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        windowContext = new ContextThemeWrapper(displayContext, R.style.AppTheme);
        manager = displayContext.getSystemService(WindowManager.class);
        root = new LinearLayout(windowContext);
        root.setOrientation(LinearLayout.VERTICAL);
        bubble = new TextView(windowContext);
        bubble.setId(R.id.floating_bubble);
        bubble.setContentDescription("ScreenOhRecord floating controls");
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextSize(25);
        bubble.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bubble.setElevation(dp(7));
        LinearLayout.LayoutParams bubbleLayout = new LinearLayout.LayoutParams(dp(54), dp(54));
        bubbleLayout.gravity = Gravity.END;
        root.addView(bubble, bubbleLayout);

        panel = new LinearLayout(windowContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(shape(Color.rgb(22, 38, 43), 18));
        label = new TextView(windowContext);
        label.setId(R.id.floating_status);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        panel.addView(label);
        start = control("Start recording", R.id.floating_start, Color.rgb(99, 230, 195));
        start.setOnClickListener(view -> {
            expanded = false;
            android.content.SharedPreferences options = RecordingOptions.get(this);
            CaptureActivity.launch(this, options.getBoolean("microphone", true), options.getInt("quality", 720));
            refresh();
        });
        pause = control("Pause", R.id.floating_pause, Color.rgb(199, 223, 218));
        pause.setOnClickListener(view -> command(RecordingService.status().state == RecordingService.State.PAUSED
                ? RecordingService.RESUME : RecordingService.PAUSE));
        stop = control("Stop & save", R.id.floating_stop, Color.rgb(255, 116, 105));
        stop.setOnClickListener(view -> command(RecordingService.STOP));
        Button discard = control("Delete and Close", R.id.floating_discard, Color.rgb(255, 116, 105));
        discard.setOnClickListener(view -> {
            if (!RecordingService.status().active()) return;
            closeAfterDiscard = true;
            command(RecordingService.DISCARD);
        });
        Button hide = control("Hide bubble", R.id.floating_hide, Color.rgb(174, 193, 193));
        hide.setOnClickListener(view -> stopSelf());
        LinearLayout.LayoutParams panelLayout = new LinearLayout.LayoutParams(dp(252), LinearLayout.LayoutParams.WRAP_CONTENT);
        panelLayout.topMargin = dp(8);
        root.addView(panel, panelLayout);

        position = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.LEFT;
        position.setTitle("ScreenOhRecord floating controls");
        Rect bounds = bounds();
        android.content.SharedPreferences saved = getSharedPreferences("floating_position", MODE_PRIVATE);
        position.x = saved.getInt("x", bounds.width() - dp(60));
        position.y = saved.getInt("y", bounds.height() / 3);
        rightEdge = position.x > bounds.width() / 2;
        bubble.setOnClickListener(view -> { expanded = !expanded; refresh(); });
        installDrag();
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!dragging) position.x = rightEdge ? bounds().width() - root.getWidth() : 0;
            clamp();
        });
        try {
            panel.setVisibility(View.GONE);
            manager.addView(root, position);
            visible = true;
        } catch (RuntimeException error) {
            android.util.Log.w("ScreenOhRecord", "Could not attach floating controls", error);
            root = null;
            Toast.makeText(this, "Android could not display the floating controls.", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void installDrag() {
        int slop = ViewConfiguration.get(windowContext).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY();
                        startX = position.x; startY = position.y; moved = false; dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                        if (Math.hypot(dx, dy) > slop) { moved = true; dragging = true; }
                        if (moved) {
                            expanded = false;
                            panel.setVisibility(View.GONE);
                            position.x = startX + Math.round(dx);
                            position.y = startY + Math.round(dy);
                            clamp();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        dragging = false;
                        if (!moved) view.performClick();
                        else {
                            rightEdge = position.x + root.getWidth() / 2 > bounds().width() / 2;
                            position.x = rightEdge ? bounds().width() - root.getWidth() : 0;
                            refresh();
                            getSharedPreferences("floating_position", MODE_PRIVATE).edit()
                                    .putInt("x", position.x).putInt("y", position.y).apply();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL: dragging = false; clamp(); return true;
                    default: return false;
                }
            }
        });
    }

    private void refresh() {
        if (root == null || !visible) return;
        RecordingService.Status current = RecordingService.status();
        if (closeAfterDiscard && !current.active()) {
            closeAfterDiscard = false;
            if ("Recording permanently deleted".equals(current.message)) { stopSelf(); return; }
            Toast.makeText(this, current.message, Toast.LENGTH_LONG).show();
        }
        boolean requesting = CaptureActivity.isRequestActive();
        root.setVisibility(requesting ? View.GONE : View.VISIBLE);
        bubble.setText(current.state == RecordingService.State.PAUSED ? "Ⅱ" : "●");
        bubble.setTextColor(current.active() ? Color.rgb(255, 116, 105) : Color.rgb(99, 230, 195));
        bubble.setBackground(shape(Color.rgb(22, 38, 43), 27));
        label.setText(current.active() ? "ScreenOhRecord · " + current.state.name().toLowerCase(java.util.Locale.ROOT)
                : "ScreenOhRecord · ready");
        start.setVisibility(current.active() ? View.GONE : View.VISIBLE);
        start.setEnabled(!requesting && !current.active());
        boolean controllable = current.state == RecordingService.State.RECORDING || current.state == RecordingService.State.PAUSED;
        pause.setEnabled(controllable);
        stop.setEnabled(controllable);
        pause.setText(current.state == RecordingService.State.PAUSED ? "Resume" : "Pause");
        pause.setVisibility(current.active() ? View.VISIBLE : View.GONE);
        stop.setVisibility(current.active() ? View.VISIBLE : View.GONE);
        View discard = root.findViewById(R.id.floating_discard);
        discard.setEnabled(controllable);
        discard.setVisibility(current.active() ? View.VISIBLE : View.GONE);
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams bubbleLayout = (LinearLayout.LayoutParams) bubble.getLayoutParams();
        bubbleLayout.gravity = rightEdge ? Gravity.END : Gravity.START;
        bubble.setLayoutParams(bubbleLayout);
        root.post(this::clamp);
    }

    private void command(String action) {
        if (RecordingService.status().active()) startService(new Intent(this, RecordingService.class).setAction(action));
    }

    private Button control(String title, int id, int color) {
        Button button = new Button(windowContext);
        button.setId(id); button.setText(title); button.setAllCaps(false);
        button.setTextColor(Color.rgb(16, 28, 32));
        button.setBackground(shape(color, 10));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        layout.topMargin = dp(8);
        panel.addView(button, layout);
        return button;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        return shape;
    }

    private Rect bounds() {
        if (Build.VERSION.SDK_INT >= 30) return manager.getCurrentWindowMetrics().getBounds();
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private void clamp() {
        if (root == null || !visible || root.getWidth() == 0) return;
        Rect bounds = bounds();
        position.x = Math.max(0, Math.min(position.x, Math.max(0, bounds.width() - root.getWidth())));
        position.y = Math.max(dp(30), Math.min(position.y, Math.max(dp(30), bounds.height() - root.getHeight() - dp(50))));
        if (appliedX == position.x && appliedY == position.y && appliedWidth == root.getWidth()
                && appliedHeight == root.getHeight()) return;
        try {
            manager.updateViewLayout(root, position);
            appliedX = position.x; appliedY = position.y;
            appliedWidth = root.getWidth(); appliedHeight = root.getHeight();
        }
        catch (RuntimeException error) {
            android.util.Log.w("ScreenOhRecord", "Floating controls detached; closing overlay service", error);
            stopSelf();
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (root != null) root.post(this::clamp);
    }

    @Override public void onDestroy() {
        visible = false;
        RecordingService.removeStatusListener(recordingListener);
        CaptureActivity.removeRequestListener(requestListener);
        if (appOps != null) appOps.stopWatchingMode(permissionListener);
        if (root != null) {
            try { manager.removeViewImmediate(root); }
            catch (IllegalArgumentException ignored) { /* Android already removed a revoked overlay. */ }
            root = null;
        }
        handler.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
