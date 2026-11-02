package com.clipforge.core.compress;

import com.clipforge.core.FfmpegTestSupport;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.job.JobExecutor;
import com.clipforge.core.job.JobQueue;
import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.JobType;
import com.clipforge.core.project.Project;
import com.clipforge.core.telemetry.TelemetryService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Optional check on real footage, skipped unless {@code -Dclipforge.realClips=a.mp4;b.mp4} and
 * {@code -Dclipforge.realOut=dir} are given. Never runs in CI and never writes next to the sources.
 */
class RealFootageCheck {

    @Test
    void readRealTelemetry() throws Exception {
        String clips = System.getProperty("clipforge.realClips");
        assumeTrue(clips != null, "real footage not configured");
        FfmpegTools tools = FfmpegTestSupport.requireFfmpeg();
        var t = new TelemetryService(tools, ZoneId.systemDefault())
                .read(Path.of(clips.split(";")[0]));
        System.out.printf("TELEMETRY: source=%s samples=%d positions=%d first=%s last=%s%n", t.source(),
                t.samples().size(), t.positionCount(), t.samples().isEmpty() ? null : t.samples().getFirst(),
                t.samples().isEmpty() ? null : t.samples().getLast().time());
    }

    /** -Dclipforge.hideClip=<one clip> -Dclipforge.realOut=<dir>: hides plates and faces in a real clip. */
    @Test
    void hidePlatesInRealClip() throws Exception {
        String clip = System.getProperty("clipforge.hideClip");
        String outDir = System.getProperty("clipforge.realOut");
        assumeTrue(clip != null && outDir != null, "real footage not configured");
        FfmpegTools tools = FfmpegTestSupport.requireFfmpeg();
        MediaInfo info = new ProbeService(tools).probe(Path.of(clip)).orElseThrow();
        CompressOptions o = CompressOptions.defaults(EncoderProfile.AV1_NVENC)
                .withHide(new CompressOptions.Hide(true, true));
        CompressSpec spec = CompressPlanner.forFile(Path.of(clip), Files.size(Path.of(clip)), info,
                Files.createDirectories(Path.of(outDir)), o, List.of());
        JobRecord job = JobRecord.compress(spec, List.of(), Clock.systemUTC().instant());
        long t0 = System.nanoTime();
        JobQueue q = new JobQueue(new Project(), Map.<JobType, JobExecutor>of(JobType.COMPRESS,
                new SegmentedEncoder(tools, SegmentedEncoderTest.testDetectors())), () -> { },
                new JobQueue.Listener() {
                    @Override
                    public void jobChanged(JobRecord j) {
                    }

                    @Override
                    public void progress(JobRecord j, Progress p) {
                    }

                    @Override
                    public void idle() {
                    }
                }, Clock.systemUTC());
        q.submit(List.of(job));
        assertTrue(q.awaitIdle(Duration.ofMinutes(30)));
        double wall = (System.nanoTime() - t0) / 1e9;
        System.out.printf("hide: %s %.0f s video in %.0f s (%.2fx realtime)%n", job.getState(),
                info.duration().toMillis() / 1000.0, wall, info.duration().toMillis() / 1000.0 / wall);
        assertEquals(JobState.DONE, job.getState(), job.getErrorKey() + " " + job.getErrorDetail());
    }

    @Test
    void compressRealClips() throws Exception {
        String clips = System.getProperty("clipforge.realClips");
        String outDir = System.getProperty("clipforge.realOut");
        assumeTrue(clips != null && outDir != null, "real footage not configured");
        FfmpegTools tools = FfmpegTestSupport.requireFfmpeg();
        ProbeService probe = new ProbeService(tools);
        List<String> inputs = Arrays.asList(clips.split(";"));
        long ms = 0;
        long bytes = 0;
        List<CompressInput> sources = new ArrayList<>();
        for (String in : inputs) {
            MediaInfo info = probe.probe(Path.of(in)).orElseThrow();
            sources.add(CompressInput.of(Path.of(in), info));
            ms += info.duration().toMillis();
            bytes += Files.size(Path.of(in));
        }
        CompressOptions o = CompressOptions.defaults(EncoderProfile.AV1_NVENC);
        Path out = Files.createDirectories(Path.of(outDir)).resolve("real_compressed_" + System.currentTimeMillis() + ".mp4");
        CompressSpec spec = new CompressSpec(sources, out.toString(), o, ms, bytes, true);
        Project project = new Project();
        JobRecord job = JobRecord.compress(spec, List.of(), Clock.systemUTC().instant());
        List<Double> speeds = new ArrayList<>();
        JobQueue q = new JobQueue(project, Map.<JobType, JobExecutor>of(JobType.COMPRESS, new SegmentedEncoder(tools)),
                () -> { }, new JobQueue.Listener() {
            @Override
            public void jobChanged(JobRecord j) {
            }

            @Override
            public void progress(JobRecord j, Progress p) {
                speeds.add(p.speed());
            }

            @Override
            public void idle() {
            }
        }, Clock.systemUTC());
        long start = System.nanoTime();
        q.submit(List.of(job));
        q.awaitIdle(Duration.ofMinutes(30));
        double wall = (System.nanoTime() - start) / 1e9;
        MediaInfo m = probe.probe(out).orElseThrow();
        System.out.printf("REAL: %s -> %s, %.1f s media in %.1f s wall (%.1fx), %d segments, %s %s%n",
                bytes, Files.size(out), ms / 1000.0, wall, ms / 1000.0 / wall,
                job.getCompress().getSegments().size(), m.videoCodec(), m.audioCodec());
        assertEquals(ms / 1000.0, m.duration().toMillis() / 1000.0, 1.5);
    }
}
