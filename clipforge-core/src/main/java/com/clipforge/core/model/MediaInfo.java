package com.clipforge.core.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Technical facts about a media file as reported by ffprobe.
 *
 * @param duration     container duration
 * @param videoCodec   codec name of the first video stream (e.g. {@code hevc}), {@code null} if none
 * @param width        width of the first video stream in pixels, 0 if unknown
 * @param height       height of the first video stream in pixels, 0 if unknown
 * @param frameRate    average frame rate, 0 if unknown
 * @param audioCodec   codec name of the first audio stream, {@code null} if none
 * @param dataStreams  number of data / unknown streams (GPS, telemetry, timecode)
 * @param creationTime {@code creation_time} container tag, {@code null} if absent
 * @param videoStart   {@code start_time} of the first video stream (often non-zero, e.g. after an edit list)
 * @param audioStart   {@code start_time} of the first audio stream
 * @param audioChannels channel count of the first audio stream, 0 if unknown
 */
public record MediaInfo(
        Duration duration,
        String videoCodec,
        int width,
        int height,
        double frameRate,
        String audioCodec,
        int dataStreams,
        Instant creationTime,
        Duration videoStart,
        Duration audioStart,
        int audioChannels) {

    public MediaInfo {
        videoStart = videoStart == null ? Duration.ZERO : videoStart;
        audioStart = audioStart == null ? Duration.ZERO : audioStart;
    }

    /** Media info without stream start offsets (both zero). */
    public MediaInfo(Duration duration, String videoCodec, int width, int height, double frameRate,
                     String audioCodec, int dataStreams, Instant creationTime) {
        this(duration, videoCodec, width, height, frameRate, audioCodec, dataStreams, creationTime,
                Duration.ZERO, Duration.ZERO, 0);
    }

    public boolean hasVideo() {
        return videoCodec != null;
    }

    public boolean hasAudio() {
        return audioCodec != null;
    }

    /** Clips can be stream-copied into one file only if this key matches. */
    public String compatibilityKey() {
        return videoCodec + ":" + width + "x" + height + ":" + audioCodec;
    }

    public String resolution() {
        return width > 0 ? width + "x" + height : "?";
    }
}
