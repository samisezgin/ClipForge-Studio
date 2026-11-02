package com.clipforge.core.audio;

import com.clipforge.core.FfmpegTestSupport;
import com.clipforge.core.ffmpeg.FfmpegResult;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.model.MediaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicRendererTest {

    @TempDir
    Path tmp;

    private FfmpegTools tools;
    private MusicRenderer renderer;

    @BeforeEach
    void setUp() {
        tools = FfmpegTestSupport.requireFfmpeg();
        renderer = new MusicRenderer(tools);
    }

    private Path tone(String name, int hz, double seconds) throws Exception {
        Path f = tmp.resolve(name);
        FfmpegTestSupport.run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=" + hz + ":sample_rate=44100:duration=" + seconds, "-c:a", "libmp3lame", f.toString()));
        return f;
    }

    private Path render(MusicSettings s, double seconds, Path original) throws Exception {
        Path out = tmp.resolve("music-" + System.nanoTime() + ".m4a");
        FfmpegResult r = renderer.render(s, seconds, original, tmp.resolve("work"), out, null);
        assertTrue(r.success(), r.stderrText());
        return out;
    }

    /** Silent stretches (start, end) found by silencedetect. */
    private List<double[]> silences(Path f) throws Exception {
        Process p = new ProcessBuilder(tools.ffmpeg().toString(), "-hide_banner", "-i", f.toString(),
                "-af", "silencedetect=n=-45dB:d=0.25", "-f", "null", "-").redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        List<double[]> list = new ArrayList<>();
        Matcher start = Pattern.compile("silence_start: ([0-9.]+)").matcher(out);
        Matcher end = Pattern.compile("silence_end: ([0-9.]+)").matcher(out);
        while (start.find()) {
            double s = Double.parseDouble(start.group(1));
            double e = end.find() ? Double.parseDouble(end.group(1)) : Double.MAX_VALUE;
            list.add(new double[]{s, e});
        }
        return list;
    }

    private MediaInfo probe(Path f) {
        return new ProbeService(tools).probe(f).orElseThrow();
    }

    private static MusicSettings settings(List<Path> tracks, boolean loop, double xf, boolean fade) {
        return new MusicSettings(tracks.stream().map(Path::toString).toList(), loop, xf, fade, 3, 1.0, false, 0.2);
    }

    @Test
    void shortTrackLoopsSeamlesslyForTheWholeVideoAndFadesOut() throws Exception {
        Path t = tone("short.mp3", 440, 5);
        Path out = render(settings(List.of(t), true, 1.0, true), 20, null);
        MediaInfo m = probe(out);
        assertEquals(20.0, m.duration().toMillis() / 1000.0, 0.1);
        assertEquals("aac", m.audioCodec());
        List<double[]> silent = silences(out);
        // only the very end of the fade-out may be (nearly) silent - no gaps at loop restarts
        assertTrue(silent.stream().allMatch(s -> s[0] > 18.5), "no silence before the fade-out: " + silent.size());
    }

    @Test
    void withoutLoopTheMusicPlaysOnceThenSilence() throws Exception {
        Path t = tone("once.mp3", 440, 4);
        Path out = render(settings(List.of(t), false, 1.0, false), 12, null);
        assertEquals(12.0, probe(out).duration().toMillis() / 1000.0, 0.1, "video length is kept");
        List<double[]> silent = silences(out);
        assertEquals(1, silent.size());
        assertEquals(4.0, silent.getFirst()[0], 0.3, "silence starts when the track ends");
    }

    @Test
    void fadeOutCanBeTurnedOff() throws Exception {
        Path t = tone("loud.mp3", 440, 5);
        Path out = render(settings(List.of(t), true, 0, false), 8, null);
        assertTrue(silences(out).isEmpty(), "full volume until the last frame");
    }

    @Test
    void customFadeOutLength() throws Exception {
        Path t = tone("fade.mp3", 440, 30);
        MusicSettings s = new MusicSettings(List.of(t.toString()), true, 0, true, 5, 1.0, false, 0.2);
        Path out = render(s, 20, null);
        List<double[]> silent = silences(out);
        assertTrue(silent.isEmpty() || silent.getFirst()[0] > 15, "nothing is faded before the last 5 s");
    }

    @Test
    void playlistWithCrossfadeBetweenTracks() throws Exception {
        Path a = tone("a.mp3", 330, 4);
        Path b = tone("b.mp3", 550, 4);
        Path out = render(settings(List.of(a, b), true, 1.0, false), 25, null);
        assertEquals(25.0, probe(out).duration().toMillis() / 1000.0, 0.1);
        assertTrue(silences(out).isEmpty(), "no gaps between tracks or loops");
    }

    @Test
    void mixesTheOriginalAudioQuietly() throws Exception {
        Path music = tone("m.mp3", 440, 3);
        Path original = tmp.resolve("orig.m4a");
        FfmpegTestSupport.run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=1000:duration=10", "-c:a", "aac", original.toString()));
        MusicSettings s = new MusicSettings(List.of(music.toString()), false, 0, false, 3, 1.0, true, 0.3);
        Path out = render(s, 10, original);
        // the music stops after 3 s, but the original keeps the track from being silent
        assertTrue(silences(out).isEmpty());
    }

    @Test
    void unreadableTrackIsReported() throws Exception {
        Path bad = Files.writeString(tmp.resolve("not music.mp3"), "nope");
        MusicRenderer.TrackException e = assertThrows(MusicRenderer.TrackException.class,
                () -> renderer.render(settings(List.of(bad), true, 1, true), 5, null, tmp.resolve("w"),
                        tmp.resolve("o.m4a"), null));
        assertEquals("not music.mp3", e.track());
    }
}
