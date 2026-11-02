package com.clipforge.core.scan;

import com.clipforge.core.model.Clip;
import com.clipforge.core.model.Session;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static com.clipforge.core.TestClips.clip;
import static com.clipforge.core.TestClips.copying;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionGrouperTest {

    private static final Duration TWO_MIN = Duration.ofSeconds(120);
    private final SessionGrouper grouper = new SessionGrouper(SessionGrouper.DEFAULT_TOLERANCE);

    private static LocalDateTime t(int h, int m, int s) {
        return LocalDateTime.of(2026, 11, 1, h, m, s);
    }

    @Test
    void consecutiveClipsFormOneSessionAndGapsSplit() {
        List<Clip> clips = List.of(
                clip("a", t(9, 24, 35), TWO_MIN, "F"),
                clip("b", t(23, 37, 33), TWO_MIN, "F"),
                clip("c", t(23, 39, 33), TWO_MIN, "F"),
                clip("d", t(23, 41, 34), TWO_MIN, "F")); // 1 s late: within tolerance
        List<Session> sessions = grouper.group(clips);
        assertEquals(2, sessions.size());
        assertEquals(1, sessions.get(0).clips().size());
        assertNull(sessions.get(0).gapBefore());
        assertEquals(3, sessions.get(1).clips().size());
        assertEquals(Duration.between(t(9, 26, 35), t(23, 37, 33)), sessions.get(1).gapBefore());
    }

    @Test
    void channelsAreGroupedSeparately() {
        List<Clip> clips = List.of(
                clip("f1", t(10, 0, 0), TWO_MIN, "F"),
                clip("r1", t(10, 0, 0), TWO_MIN, "R"),
                clip("f2", t(10, 2, 0), TWO_MIN, "F"),
                clip("r2", t(10, 2, 0), TWO_MIN, "R"));
        List<Session> sessions = grouper.group(clips);
        assertEquals(2, sessions.size());
        assertEquals("F", sessions.get(0).channel());
        assertEquals("R", sessions.get(1).channel());
        sessions.forEach(s -> assertEquals(2, s.clips().size()));
    }

    @Test
    void formatChangeStartsNewSession() {
        List<Clip> clips = List.of(
                clip("a", t(10, 0, 0), TWO_MIN, null, "hevc", 2304, 1296),
                clip("b", t(10, 2, 0), TWO_MIN, null, "h264", 1920, 1080));
        assertEquals(2, grouper.group(clips).size());
    }

    @Test
    void clipStillCopyingStaysInItsSession() {
        List<Clip> clips = List.of(
                clip("a", t(10, 0, 0), TWO_MIN, "F"),
                copying("b", t(10, 2, 0), "F"),
                clip("c", t(10, 4, 0), TWO_MIN, "F"));
        List<Session> sessions = grouper.group(clips);
        assertEquals(1, sessions.size(), "unknown duration is estimated from the typical clip length");
        assertEquals(3, sessions.getFirst().clips().size());
    }
}
