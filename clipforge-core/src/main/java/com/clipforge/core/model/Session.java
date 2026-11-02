package com.clipforge.core.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A continuous run of clips from the same channel without significant time gaps.
 *
 * @param clips     clips ordered by start time (never empty)
 * @param gapBefore time between the end of the previous session of the same channel and this one,
 *                  {@code null} for the first session
 */
public record Session(List<Clip> clips, Duration gapBefore) {

    public Session {
        if (clips.isEmpty()) {
            throw new IllegalArgumentException("A session needs at least one clip");
        }
        clips = List.copyOf(clips);
    }

    public LocalDateTime start() {
        return clips.getFirst().start();
    }

    public LocalDateTime end() {
        return clips.getLast().end();
    }

    public Duration duration() {
        return clips.stream().map(Clip::duration).reduce(Duration.ZERO, Duration::plus);
    }

    public long totalSize() {
        return clips.stream().mapToLong(Clip::size).sum();
    }

    public String channel() {
        return clips.getFirst().channel();
    }

    /** Stable identifier: channel plus start time of the first clip. */
    public String id() {
        return (channel() == null ? "" : channel() + "@") + start();
    }
}
