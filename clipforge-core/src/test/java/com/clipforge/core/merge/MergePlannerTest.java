package com.clipforge.core.merge;

import com.clipforge.core.model.Clip;
import com.clipforge.core.model.Session;
import com.clipforge.core.scan.SessionGrouper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.clipforge.core.TestClips.clip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergePlannerTest {

    private static final Duration TWO_MIN = Duration.ofSeconds(120);

    @TempDir
    Path out;

    private static LocalDateTime t(int day, int h, int m) {
        return LocalDateTime.of(2026, 11, day, h, m, 0);
    }

    private final List<Clip> clips = List.of(
            clip("a.mp4", t(1, 9, 24), TWO_MIN, "F"),
            clip("b.mp4", t(1, 23, 37), TWO_MIN, "F"),
            clip("c.mp4", t(1, 23, 39), TWO_MIN, "F"),
            clip("d.mp4", t(2, 8, 0), TWO_MIN, "F"));
    private final List<Session> sessions = new SessionGrouper(SessionGrouper.DEFAULT_TOLERANCE).group(clips);
    private final Set<Path> all = clips.stream().map(Clip::path).collect(Collectors.toSet());

    @Test
    void perSessionCreatesOneOutputEach() throws Exception {
        List<MergeSpec> specs = MergePlanner.plan(sessions, all, MergeMode.PER_SESSION, out, MergeOptions.DEFAULT);
        assertEquals(3, specs.size());
        assertEquals(out.resolve("20261101_2337-2341_F_merged.mp4").toString(), specs.get(1).output());
        assertEquals(2, specs.get(1).inputs().size());
        assertEquals(240_000, specs.get(1).totalDurationMs());
    }

    @Test
    void allInOneSpansDays() throws Exception {
        List<MergeSpec> specs = MergePlanner.plan(sessions, all, MergeMode.ALL_IN_ONE, out, MergeOptions.DEFAULT);
        assertEquals(1, specs.size());
        assertEquals(4, specs.getFirst().inputs().size());
        assertEquals(out.resolve("20261101_0924-20261102_0802_F_merged.mp4").toString(), specs.getFirst().output());
    }

    @Test
    void respectsSelectionAndSkipsEmptySessions() throws Exception {
        Set<Path> some = Set.of(clips.get(2).path());
        List<MergeSpec> specs = MergePlanner.plan(sessions, some, MergeMode.PER_SESSION, out, MergeOptions.DEFAULT);
        assertEquals(1, specs.size());
        assertEquals(List.of(clips.get(2).path().toString()), specs.getFirst().inputs());
    }

    @Test
    void neverReusesAnExistingFileName() throws Exception {
        Files.createFile(out.resolve("20261101_0924-0926_F_merged.mp4"));
        List<MergeSpec> specs = MergePlanner.plan(sessions, all, MergeMode.PER_SESSION, out, MergeOptions.DEFAULT);
        assertTrue(specs.getFirst().output().endsWith("20261101_0924-0926_F_merged (2).mp4"));
    }

    @Test
    void mixedFormatsInOneFileAreRejected() {
        List<Clip> mixed = List.of(
                clip("a.mp4", t(1, 10, 0), TWO_MIN, null, "hevc", 2304, 1296),
                clip("b.mp4", t(1, 12, 0), TWO_MIN, null, "h264", 1920, 1080));
        List<Session> s = new SessionGrouper(SessionGrouper.DEFAULT_TOLERANCE).group(mixed);
        Set<Path> sel = mixed.stream().map(Clip::path).collect(Collectors.toSet());
        IncompatibleClipsException e = assertThrows(IncompatibleClipsException.class,
                () -> MergePlanner.plan(s, sel, MergeMode.ALL_IN_ONE, out, MergeOptions.DEFAULT));
        assertEquals(2, e.formats().size());
    }
}
