package com.clipforge.core;

import com.clipforge.core.model.Clip;
import com.clipforge.core.model.ClipStatus;
import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.model.TimestampSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/** Builders for in-memory clips used by unit tests. */
public final class TestClips {

    private TestClips() {
    }

    public static Clip clip(String name, LocalDateTime start, Duration duration, String channel) {
        return clip(name, start, duration, channel, "hevc", 2304, 1296);
    }

    public static Clip clip(String name, LocalDateTime start, Duration duration, String channel,
                            String codec, int w, int h) {
        MediaInfo m = new MediaInfo(duration, codec, w, h, 30, "aac", 0, null);
        return new Clip(Path.of("/videos", name).toAbsolutePath(), 160_000_000L, Instant.EPOCH, start,
                TimestampSource.FILE_NAME, channel, -1, m, ClipStatus.READY);
    }

    public static Clip copying(String name, LocalDateTime start, String channel) {
        return new Clip(Path.of("/videos", name).toAbsolutePath(), 60_000_000L, Instant.EPOCH, start,
                TimestampSource.FILE_NAME, channel, -1, null, ClipStatus.COPYING);
    }
}
