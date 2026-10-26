package com.screenohrecord.app;

/** Pure recording policy. ScreenOhRecord 1.0.0. */
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

    public static String elapsed(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }
}
