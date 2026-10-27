package com.screenohrecord.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Local, bounded, memory-only previews. Binding/cache operations run on the UI thread. */
final class RecordingThumbnails implements AutoCloseable {
    private static final int WIDTH = 256, HEIGHT = 144;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final Map<String, Request> pending = new HashMap<>();
    private volatile boolean closed;
    private static final class Request {
        final CancellationSignal signal = new CancellationSignal();
        final ArrayList<WeakReference<ImageView>> views = new ArrayList<>();
        Future<?> future;
        void cancel() { signal.cancel(); if (future != null) future.cancel(true); }
    }
    RecordingThumbnails(Context context) { this.context = context.getApplicationContext(); }
    void bind(Uri uri, ImageView view) {
        if (closed) return;
        String key = uri.toString(); view.setTag(key); view.setTag(R.id.thumbnail_loaded, false);
        Bitmap hit = cache.get(key);
        if (hit != null) { view.setImageBitmap(hit); view.setTag(R.id.thumbnail_loaded, true); return; }
        view.setImageResource(android.R.drawable.ic_media_play);
        Request request = pending.get(key);
        if (request != null) { request.views.add(new WeakReference<>(view)); return; }
        Request load = new Request(); load.views.add(new WeakReference<>(view)); pending.put(key, load);
        load.future = worker.submit(() -> {
            Bitmap bitmap = decode(uri, load.signal);
            main.post(() -> {
                // Deleting/rebinding a row invalidates even a native decode that could not be cancelled.
                if (closed || pending.get(key) != load || load.signal.isCanceled()) return;
                pending.remove(key);
                if (bitmap == null) return;
                cache.put(key, bitmap);
                for (WeakReference<ImageView> reference : load.views) {
                    ImageView target = reference.get();
                    if (target != null && key.equals(target.getTag())) {
                        target.setImageBitmap(bitmap); target.setTag(R.id.thumbnail_loaded, true);
                    }
                }
            });
        });
    }
    private Bitmap decode(Uri uri, CancellationSignal signal) {
        // Decode directly: provider thumbnail APIs can create their own on-disk preview caches.
        if (closed || signal.isCanceled() || Thread.currentThread().isInterrupted()) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            Bitmap frame = retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT);
            if (frame == null) frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT);
            return frame;
        } catch (RuntimeException error) {
            android.util.Log.w("ScreenOhRecord", "Local recording preview is unavailable", error);
            return null;
        } finally { try { retriever.release(); } catch (IOException ignored) { } }
    }
    void cancelPending() {
        for (Request request : pending.values()) request.cancel();
        pending.clear();
    }
    void forget(Uri uri) {
        String key = uri.toString(); cache.remove(key);
        Request request = pending.remove(key);
        if (request != null) {
            request.cancel();
            for (WeakReference<ImageView> reference : request.views) {
                ImageView view = reference.get();
                if (view != null && key.equals(view.getTag())) clear(view);
            }
        }
    }
    static void clear(ImageView view) {
        view.setTag(null); view.setTag(R.id.thumbnail_loaded, false); view.setImageDrawable(null);
    }
    @Override public void close() {
        closed = true; cancelPending(); cache.evictAll(); worker.shutdownNow(); main.removeCallbacksAndMessages(null);
    }
}
