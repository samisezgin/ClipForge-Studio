package com.clipforge.core.mp4;

import com.clipforge.core.FfmpegTestSupport;
import com.clipforge.core.ffmpeg.FfmpegTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Encodes a 10 s clip as three independent fragmented segments and joins them byte-wise. */
class FragmentedMp4Test {

    @TempDir
    Path tmp;

    private Path segment(FfmpegTools t, Path src, double from, double to, String name) throws Exception {
        Path out = tmp.resolve(name);
        FfmpegTestSupport.run(List.of(t.ffmpeg().toString(), "-v", "error", "-y", "-ss", String.valueOf(from),
                "-copyts", "-i", src.toString(), "-map", "0:v:0",
                "-vf", "trim=start=" + from + ":end=" + to, "-fps_mode", "passthrough",
                "-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-video_track_timescale", "90000",
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-f", "mp4", out.toString()));
        return out;
    }

    private static List<Double> packetTimes(FfmpegTools t, Path file) throws Exception {
        Process p = new ProcessBuilder(t.ffprobe().toString(), "-v", "error", "-select_streams", "v",
                "-show_entries", "packet=pts_time", "-of", "csv=p=0", file.toString()).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        List<Double> times = new ArrayList<>();
        out.lines().filter(l -> !l.isBlank()).forEach(l -> times.add(Double.parseDouble(l.trim())));
        return times;
    }

    @Test
    void segmentsJoinIntoOneContinuousStream() throws Exception {
        FfmpegTools t = FfmpegTestSupport.requireFfmpeg();
        Path src = tmp.resolve("src.mp4");
        FfmpegTestSupport.run(List.of(t.ffmpeg().toString(), "-v", "error", "-y", "-f", "lavfi", "-i",
                "testsrc2=size=320x180:rate=30:duration=10", "-c:v", "libx264", "-preset", "ultrafast",
                "-g", "30", "-pix_fmt", "yuv420p", src.toString()));
        Path s1 = segment(t, src, 0, 4, "s1.mp4");
        Path s2 = segment(t, src, 4, 8, "s2.mp4");
        Path s3 = segment(t, src, 8, 10, "s3.mp4");

        Path out = tmp.resolve("joined.mp4");
        FragmentedMp4.Info base = FragmentedMp4.inspect(s1);
        assertEquals(90000, base.timescale());
        assertEquals(120, base.samples());

        FragmentedMp4.AppendResult r = FragmentedMp4.append(out, null, s1, 0, 1);
        r = FragmentedMp4.append(out, base, s2, r.endTicks(), r.nextSequence());
        long sizeBeforeLast = r.fileSize();
        r = FragmentedMp4.append(out, base, s3, r.endTicks(), r.nextSequence());
        assertEquals(10 * 90000, r.endTicks(), 3000, "end time ~10 s");

        List<Double> times = packetTimes(t, out);
        assertEquals(300, times.size(), "every frame exactly once");
        for (int i = 1; i < times.size(); i++) {
            assertTrue(times.get(i) > times.get(i - 1), "monotonic at " + i);
        }
        assertEquals(9.967, times.getLast(), 0.05);

        FragmentedMp4.Info joined = FragmentedMp4.inspect(out);
        assertEquals(300, joined.samples());
        assertEquals(r.endTicks(), FragmentedMp4.endTicks(joined));

        // simulate a crash in the middle of the last append and recover by truncation
        Files.write(out, new byte[]{0, 0, 1, 0, 'm', 'o', 'o', 'f', 1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        FragmentedMp4.Info damaged = FragmentedMp4.inspect(out);
        assertEquals(300, damaged.samples(), "incomplete trailing box is ignored");
        FragmentedMp4.truncate(out, FragmentedMp4.lastCompleteFragmentEnd(damaged));
        assertEquals(r.fileSize(), Files.size(out));
        assertTrue(sizeBeforeLast < Files.size(out));
    }
}
