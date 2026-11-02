package com.clipforge.core.util;

import java.time.Duration;
import java.util.Locale;

/** Human-readable formatting of durations and sizes. */
public final class Formats {

    private Formats() {
    }

    /** {@code 1:05:09} or {@code 05:09}. */
    public static String duration(Duration d) {
        if (d == null) {
            return "--:--";
        }
        long s = Math.max(0, d.toSeconds());
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    /** Binary units with one decimal: {@code 1.6 GB}. */
    public static String size(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return u == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }
}
