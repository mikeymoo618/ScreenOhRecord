package com.screenohrecord.app;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import java.util.Arrays;

/** Recording dimensions and encoder compatibility. ScreenOhRecord 1.2.7. */
public final class RecordingConfig {
    private RecordingConfig() { }

    public static int[] chooseSize(int width, int height, int shortEdge) {
        if (width < 2 || height < 2 || shortEdge < 2) {
            throw new IllegalArgumentException("Invalid recording dimensions");
        }
        double scale = Math.min(1d, (double) shortEdge / Math.min(width, height));
        int outWidth = Math.max(2, ((int) (width * scale)) & ~1);
        int outHeight = Math.max(2, ((int) (height * scale)) & ~1);
        return new int[] {outWidth, outHeight};
    }

    public static int bitRate(int shortEdge) {
        return shortEdge >= 1080 ? 6_000_000 : 3_000_000;
    }

    static int[] encoderSize(String codec, int[] requested, int fps, int bitrate) {
        String mime = RecordingOptions.mime(codec);
        MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        if (codecs.findEncoderForFormat(videoFormat(mime, requested[0], requested[1], fps, bitrate)) != null)
            return requested;
        // Fit legacy encoders only on Android 9. Newer Android retains its selected dimensions.
        if (Build.VERSION.SDK_INT != 28) return null;
        int[] best = null;
        long bestArea = 0;
        for (MediaCodecInfo info : codecs.getCodecInfos()) {
            if (!info.isEncoder() || !Arrays.asList(info.getSupportedTypes()).contains(mime)) continue;
            MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(mime);
            MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
            double scale = Math.min(1d, Math.min((double) video.getSupportedWidths().getUpper() / requested[0],
                    (double) video.getSupportedHeights().getUpper() / requested[1]));
            for (int attempt = 0; attempt < 32; attempt++, scale *= 0.95) {
                int width = ((int) (requested[0] * scale) / video.getWidthAlignment()) * video.getWidthAlignment();
                int height = ((int) (requested[1] * scale) / video.getHeightAlignment()) * video.getHeightAlignment();
                if (width < 2 || height < 2) break;
                MediaFormat format = videoFormat(mime, width, height, fps, bitrate);
                if (!capabilities.isFormatSupported(format) || codecs.findEncoderForFormat(format) == null) continue;
                long area = (long) width * height;
                if (area > bestArea) { best = new int[]{width, height}; bestArea = area; }
                break;
            }
        }
        return best;
    }

    private static MediaFormat videoFormat(String mime, int width, int height, int fps, int bitrate) {
        MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        return format;
    }

    public static String elapsed(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
}
