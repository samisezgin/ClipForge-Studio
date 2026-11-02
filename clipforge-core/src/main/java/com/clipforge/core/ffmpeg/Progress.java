package com.clipforge.core.ffmpeg;

import java.time.Duration;

/**
 * Progress snapshot of a running FFmpeg process.
 *
 * @param processed media time written so far
 * @param total     expected total media time ({@link Duration#ZERO} if unknown)
 * @param speed     processing speed relative to real time (e.g. 35.0 for "35x"), 0 if unknown
 * @param outputBytes bytes written to the output so far, -1 if unknown
 */
public record Progress(Duration processed, Duration total, double speed, long outputBytes) {

    public static final Progress NONE = new Progress(Duration.ZERO, Duration.ZERO, 0, -1);

    /** Fraction between 0 and 1, or -1 if the total is unknown. */
    public double fraction() {
        if (total.isZero()) {
            return -1;
        }
        return Math.min(1.0, (double) processed.toMillis() / total.toMillis());
    }

    /** Estimated remaining wall-clock time, or {@code null} if it cannot be estimated. */
    public Duration eta() {
        if (total.isZero() || speed <= 0) {
            return null;
        }
        long remainingMedia = Math.max(0, total.toMillis() - processed.toMillis());
        return Duration.ofMillis(Math.round(remainingMedia / speed));
    }
}
