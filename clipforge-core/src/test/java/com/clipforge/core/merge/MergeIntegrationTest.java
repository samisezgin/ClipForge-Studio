package com.clipforge.core.merge;

import com.clipforge.core.FfmpegTestSupport;
import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.FfmpegResult;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.model.Clip;
import com.clipforge.core.model.ClipStatus;
import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.model.Session;
import com.clipforge.core.scan.ClipScanner;
import com.clipforge.core.scan.SessionGrouper;
import com.clipforge.core.scan.TimestampResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scan → group → plan → merge with real FFmpeg on tiny synthetic clips in a folder with Turkish characters. */
class MergeIntegrationTest {

    @TempDir
    Path tmp;

    private FfmpegTools tools;
    private Path folder;
    private Path out;

    @BeforeEach
    void setUp() throws Exception {
        tools = FfmpegTestSupport.requireFfmpeg();
        folder = Files.createDirectories(tmp.resolve("1 kasım araç kayıt"));
        out = Files.createDirectories(tmp.resolve("çıktı"));
        // session 1: three consecutive 3 s clips, session 2: one clip an hour later
        FfmpegTestSupport.makeClip(tools, folder.resolve("NO20261101-100000-000001F.mp4"), 3, "320x180");
        FfmpegTestSupport.makeClip(tools, folder.resolve("NO20261101-100003-000002F.mp4"), 3, "320x180");
        FfmpegTestSupport.makeClip(tools, folder.resolve("NO20261101-100006-000003F.mp4"), 3, "320x180");
        FfmpegTestSupport.makeClip(tools, folder.resolve("NO20261101-110000-000004F.mp4"), 3, "320x180");
    }

    private List<Session> scan() throws Exception {
        ClipScanner scanner = new ClipScanner(new ProbeService(tools), new TimestampResolver(ZoneId.systemDefault()),
                Clock.systemDefaultZone());
        List<Clip> clips = scanner.scan(List.of(folder), null);
        assertEquals(4, clips.size());
        clips.forEach(c -> assertEquals(ClipStatus.READY, c.status(), c.fileName()));
        return new SessionGrouper(SessionGrouper.DEFAULT_TOLERANCE).group(clips);
    }

    private static Set<Path> all(List<Session> sessions) {
        return sessions.stream().flatMap(s -> s.clips().stream()).map(Clip::path).collect(Collectors.toSet());
    }

    @Test
    void perSessionMergeIsLosslessAndComplete() throws Exception {
        List<Session> sessions = scan();
        assertEquals(2, sessions.size());

        List<MergeSpec> specs = MergePlanner.plan(sessions, all(sessions), MergeMode.PER_SESSION, out,
                MergeOptions.DEFAULT);
        MergeService merge = new MergeService(tools);
        ProbeService probe = new ProbeService(tools);
        List<Progress> seen = new ArrayList<>();
        for (MergeSpec s : specs) {
            FfmpegResult r = merge.merge(s, seen::add, new CancellationToken());
            assertTrue(r.success(), r.stderrText());
        }
        assertFalse(seen.isEmpty(), "progress is reported");
        for (MergeSpec s : specs) {
            assertFalse(Files.exists(MergeService.partPath(Path.of(s.output()))), "part file renamed away");
        }

        MediaInfo first = probe.probe(Path.of(specs.get(0).output())).orElseThrow();
        assertEquals(9.0, first.duration().toMillis() / 1000.0, 0.3);
        assertEquals("h264", first.videoCodec(), "stream copy keeps the codec");
        assertEquals("aac", first.audioCodec());
        assertEquals(0, first.dataStreams());
        MediaInfo second = probe.probe(Path.of(specs.get(1).output())).orElseThrow();
        assertEquals(3.0, second.duration().toMillis() / 1000.0, 0.3);
        assertTrue(Path.of(specs.get(0).output()).getFileName().toString().startsWith("20261101_100000-100009_F_merged"));
    }

    @Test
    void allInOneAndFaststart() throws Exception {
        List<Session> sessions = scan();
        List<MergeSpec> specs = MergePlanner.plan(sessions, all(sessions), MergeMode.ALL_IN_ONE, out,
                new MergeOptions(true));
        assertEquals(1, specs.size());
        FfmpegResult r = new MergeService(tools).merge(specs.getFirst(), null, null);
        assertTrue(r.success(), r.stderrText());
        MediaInfo m = new ProbeService(tools).probe(Path.of(specs.getFirst().output())).orElseThrow();
        assertEquals(12.0, m.duration().toMillis() / 1000.0, 0.3);
    }

    @Test
    void cancelledMergeLeavesNoFile() throws Exception {
        List<Session> sessions = scan();
        MergeSpec spec = MergePlanner.plan(sessions, all(sessions), MergeMode.ALL_IN_ONE, out,
                MergeOptions.DEFAULT).getFirst();
        CancellationToken token = new CancellationToken();
        token.cancel();
        FfmpegResult r = new MergeService(tools).merge(spec, null, token);
        assertTrue(r.cancelled());
        assertFalse(Files.exists(Path.of(spec.output())));
    }

    @Test
    void neverOverwritesExistingOutput() throws Exception {
        List<Session> sessions = scan();
        MergeSpec spec = MergePlanner.plan(sessions, all(sessions), MergeMode.ALL_IN_ONE, out,
                MergeOptions.DEFAULT).getFirst();
        Files.writeString(Path.of(spec.output()), "user data");
        try {
            new MergeService(tools).merge(spec, null, null);
        } catch (java.nio.file.FileAlreadyExistsException expected) {
            assertEquals("user data", Files.readString(Path.of(spec.output())));
            return;
        }
        throw new AssertionError("existing file must not be overwritten");
    }

    @Test
    void concatListEscapesQuotes() {
        String list = MergeService.concatList(List.of("C:\\a\\it's.mp4"));
        assertTrue(list.contains("file 'C:/a/it'\\''s.mp4'"), list);
    }
}
