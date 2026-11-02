package com.clipforge.core.scan;

import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.model.TimestampSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TimestampResolverTest {

    @Test
    void parsesDashCamName() {
        ParsedName n = TimestampResolver.parseName("NO20261101-233933-004116F.mp4");
        assertEquals(LocalDateTime.of(2026, 11, 1, 23, 39, 33), n.start());
        assertEquals("F", n.channel());
        assertEquals(4116, n.sequence());
    }

    @Test
    void parsesRearChannelWithUnderscores() {
        ParsedName n = TimestampResolver.parseName("EV20261101_120000_000012R.MP4");
        assertEquals(LocalDateTime.of(2026, 11, 1, 12, 0, 0), n.start());
        assertEquals("R", n.channel());
    }

    @Test
    void parsesPhoneNames() {
        assertEquals(LocalDateTime.of(2026, 11, 1, 23, 39, 33),
                TimestampResolver.parseName("VID_20261101_233933.mp4").start());
        assertEquals(LocalDateTime.of(2026, 11, 1, 23, 39, 33),
                TimestampResolver.parseName("PXL_20261101_233933123.mp4").start());
    }

    @Test
    void parsesGenericDateTime() {
        assertEquals(LocalDateTime.of(2026, 11, 1, 23, 39, 33),
                TimestampResolver.parseName("Trip 2026-11-01 23-39-33.mkv").start());
        assertEquals(LocalDateTime.of(2026, 11, 1, 23, 39, 33),
                TimestampResolver.parseName("Screen Recording 2026-11-01 at 23.39.33.mov").start());
    }

    @Test
    void goProChaptersOrderByFileThenChapter() {
        ParsedName first = TimestampResolver.parseName("GX010042.MP4");
        ParsedName second = TimestampResolver.parseName("GX020042.MP4");
        assertNull(first.start());
        assertEquals(4201, first.sequence());
        assertEquals(4202, second.sequence());
    }

    @Test
    void invalidDateDigitsAreIgnored() {
        assertNull(TimestampResolver.parseName("NO20261399-999999-000001F.mp4").start());
        assertNull(TimestampResolver.parseName("holiday.mp4").start());
    }

    @Test
    void fallsBackToCreationTimeThenModified() {
        TimestampResolver r = new TimestampResolver(ZoneOffset.UTC);
        Instant created = Instant.parse("2026-11-01T10:00:00Z");
        MediaInfo withTag = new MediaInfo(Duration.ofMinutes(2), "h264", 1920, 1080, 30, "aac", 0, created);
        TimestampResolver.Resolved a = r.resolve(ParsedName.EMPTY, withTag, Instant.parse("2026-11-02T00:00:00Z"));
        assertEquals(TimestampSource.CREATION_TIME, a.source());
        assertEquals(LocalDateTime.of(2026, 11, 1, 10, 0), a.start());

        MediaInfo noTag = new MediaInfo(Duration.ofMinutes(2), "h264", 1920, 1080, 30, "aac", 0, null);
        TimestampResolver.Resolved b = r.resolve(ParsedName.EMPTY, noTag, Instant.parse("2026-11-01T10:02:00Z"));
        assertEquals(TimestampSource.FILE_MODIFIED, b.source());
        assertEquals(LocalDateTime.of(2026, 11, 1, 10, 0), b.start(), "mtime minus duration");
    }
}
