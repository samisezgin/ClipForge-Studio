package com.clipforge.core.model;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One video file found by the scanner.
 *
 * @param path        absolute file path
 * @param size        file size in bytes
 * @param modified    last modification time
 * @param start       local start time of the recording
 * @param startSource where {@code start} came from
 * @param channel     camera channel parsed from the file name (e.g. {@code F} front, {@code R} rear), or {@code null}
 * @param sequence    sequence/chapter number parsed from the file name, or -1
 * @param media       ffprobe facts, {@code null} when the file could not be read
 * @param status      whether the clip can be selected
 */
public record Clip(
        Path path,
        long size,
        Instant modified,
        LocalDateTime start,
        TimestampSource startSource,
        String channel,
        long sequence,
        MediaInfo media,
        ClipStatus status) {

    public String fileName() {
        return path.getFileName().toString();
    }

    /** Duration of the clip, {@link Duration#ZERO} if unknown. */
    public Duration duration() {
        return media != null && media.duration() != null ? media.duration() : Duration.ZERO;
    }

    public LocalDateTime end() {
        return start.plus(duration());
    }

    public boolean selectable() {
        return status.selectable();
    }
}
