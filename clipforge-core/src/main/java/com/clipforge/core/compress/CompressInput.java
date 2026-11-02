package com.clipforge.core.compress;

import com.clipforge.core.model.MediaInfo;

import java.nio.file.Path;

/**
 * One source file of a compression job, with the timing facts needed for frame-exact segments.
 *
 * @param path         absolute path
 * @param durationMs   container duration; clips follow each other on the output timeline in this length
 * @param videoStartUs {@code start_time} of the video stream in microseconds (segment cut points are relative to it)
 * @param audioStartUs {@code start_time} of the audio stream in microseconds
 * @param frameRate    average frame rate, for progress reporting
 * @param startEpochMs wall-clock time the recording started (for the date/time overlay), 0 if unknown
 * @param hasAudio     the file has an audio track (a free merge fills silence for the ones without)
 * @param width        picture width (0 if unknown: projects from before 1.3)
 * @param height       picture height
 */
public record CompressInput(String path, long durationMs, long videoStartUs, long audioStartUs, double frameRate,
                            long startEpochMs, boolean hasAudio, int width, int height) {

    public CompressInput(String path, long durationMs, long videoStartUs, long audioStartUs, double frameRate,
                         long startEpochMs, boolean hasAudio) {
        this(path, durationMs, videoStartUs, audioStartUs, frameRate, startEpochMs, hasAudio, 0, 0);
    }

    /** Before 1.2 every joined source had audio, or none had (they had to share a format). */
    public CompressInput(String path, long durationMs, long videoStartUs, long audioStartUs, double frameRate,
                         long startEpochMs) {
        this(path, durationMs, videoStartUs, audioStartUs, frameRate, startEpochMs, true, 0, 0);
    }

    public static CompressInput of(Path path, MediaInfo m) {
        return of(path, m, 0);
    }

    public static CompressInput of(Path path, MediaInfo m, long startEpochMs) {
        return new CompressInput(path.toAbsolutePath().toString(), m.duration().toMillis(),
                m.videoStart().toNanos() / 1000, m.audioStart().toNanos() / 1000, m.frameRate(), startEpochMs,
                m.hasAudio(), m.width(), m.height());
    }
}
