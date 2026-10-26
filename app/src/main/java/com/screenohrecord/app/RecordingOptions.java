package com.screenohrecord.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.util.Locale;

/** Versioned local preferences shared by app, settings and floating controls. */
final class RecordingOptions {
    private RecordingOptions() { }
    static SharedPreferences get(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("capture_options", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("defaults_112", false)) {
            // Apply the approved microphone default once on upgrade; later choices are respected.
            preferences.edit().putBoolean("microphone", true).putBoolean("defaults_112", true).apply();
        }
        return preferences;
    }
    static int fps(int value) { return value == 30 ? 30 : 15; }
    static int bitrate(int value) {
        return value == 250_000 || value == 350_000 || value == 500_000
                || value == 2_000_000 || value == 4_000_000 || value == 6_000_000 ? value : 1_000_000;
    }
    @SuppressWarnings("deprecation")
    static boolean supportsHevc(Context context) {
        return supportsCodec(context, "hevc");
    }
    static String codec(Context context) {
        SharedPreferences p = get(context);
        return validCodec(p.getString("codec", p.getBoolean("hevc", false) ? "hevc" : "h264"));
    }
    static String validCodec(String value) {
        return "av1".equals(value) ? "av1" : "hevc".equals(value) ? "hevc" : "h264";
    }
    static String codecLabel(String value) {
        return "av1".equals(value) ? "AV1" : "hevc".equals(value) ? "H.265 / HEVC" : "H.264";
    }
    static String mime(String value) {
        return "av1".equals(value) ? "video/av01" : "hevc".equals(value) ? "video/hevc" : "video/avc";
    }
    @SuppressWarnings("deprecation")
    static boolean supportsCodec(Context context, String codec) {
        if ("av1".equals(codec) && android.os.Build.VERSION.SDK_INT < 33) return false;
        SharedPreferences preferences = get(context);
        DisplayMetrics metrics = new DisplayMetrics();
        context.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(metrics);
        int[] size = RecordingConfig.chooseSize(metrics.widthPixels, metrics.heightPixels,
                preferences.getInt("quality", 720) >= 1080 ? 1080 : 720);
        MediaFormat format = MediaFormat.createVideoFormat(mime(codec), size[0], size[1]);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps(preferences.getInt("fps", 15)));
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate(preferences.getInt("bitrate", 1_000_000)));
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null;
    }
    static String summary(Context context) {
        SharedPreferences p = get(context);
        double megabytes = (bitrate(p.getInt("bitrate", 1_000_000))
                + (p.getBoolean("microphone", true) ? 96_000 : 0)) * 60d / 8_000_000d;
        int rate = bitrate(p.getInt("bitrate", 1_000_000));
        String rateLabel = rate < 1_000_000 ? (rate / 1000) + " kbps" : (rate / 1_000_000) + " Mbps";
        return String.format(Locale.ROOT, "%dp · %d fps · %s · %s\nAbout %.1f MB/min (estimate)",
                p.getInt("quality", 720) >= 1080 ? 1080 : 720, fps(p.getInt("fps", 15)),
                codecLabel(codec(context)),
                rateLabel, megabytes);
    }
    static String folder(Context context) {
        return get(context).getString("folder_label", "Movies / ScreenOhRecord");
    }
}
