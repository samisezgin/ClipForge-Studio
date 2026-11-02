package com.clipforge.core.compress;

import com.clipforge.core.FfmpegTestSupport;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.job.JobExecutor;
import com.clipforge.core.job.JobQueue;
import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.mp4.FragmentedMp4;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.JobType;
import com.clipforge.core.project.Project;
import com.clipforge.core.project.ProjectStore;
import com.clipforge.core.project.RecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** End-to-end compression on a 20 s synthetic clip in 4 s segments, including stop/resume and crash recovery. */
class SegmentedEncoderTest {

    private static final int FRAMES = 600; // 20 s at 30 fps

    @TempDir
    Path tmp;

    private FfmpegTools tools;
    private EncoderProfile profile;
    private Path source;
    private Project project;
    private Path projectFile;

    @BeforeEach
    void setUp() throws Exception {
        tools = FfmpegTestSupport.requireFfmpeg();
        Set<EncoderProfile> available = EncoderCapabilities.detect(tools);
        profile = available.contains(EncoderProfile.AV1_NVENC) ? EncoderProfile.AV1_NVENC
                : available.contains(EncoderProfile.SVT_AV1) ? EncoderProfile.SVT_AV1 : null;
        assumeTrue(profile != null, "no AV1 encoder available");
        source = FfmpegTestSupport.makeClip(tools, Files.createDirectories(tmp.resolve("kaynak klasör")).resolve("ş.mp4"),
                20, "320x180");
        project = new Project();
        projectFile = tmp.resolve("p.cfproj");
    }

    private JobRecord newJob(int concurrency) throws Exception {
        MediaInfo m = new ProbeService(tools).probe(source).orElseThrow();
        CompressOptions o = new CompressOptions(profile, profile.defaultQuality(), 4, concurrency, AudioMode.KEEP);
        CompressSpec spec = CompressPlanner.forFile(source, Files.size(source), m,
                Files.createDirectories(tmp.resolve("çıktı")), o, List.of());
        return JobRecord.compress(spec, List.of(), Clock.systemUTC().instant());
    }

    private JobQueue queue(JobQueue.Listener listener) {
        return new JobQueue(project, Map.<JobType, JobExecutor>of(JobType.COMPRESS, new SegmentedEncoder(tools)),
                () -> {
                    synchronized (project) {
                        try {
                            ProjectStore.save(project, projectFile);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }, listener, Clock.systemUTC());
    }

    private int frames(Path file) throws Exception {
        Process p = new ProcessBuilder(tools.ffprobe().toString(), "-v", "error", "-select_streams", "v",
                "-count_packets", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", file.toString()).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return Integer.parseInt(out);
    }

    private void assertFinished(JobRecord job) throws Exception {
        assertEquals(JobState.DONE, job.getState(), String.valueOf(job.getErrorDetail()));
        Path out = Path.of(job.output());
        MediaInfo m = new ProbeService(tools).probe(out).orElseThrow();
        assertEquals("av1", m.videoCodec());
        assertTrue(m.hasAudio(), "audio kept");
        assertEquals(20.0, m.duration().toMillis() / 1000.0, 0.5);
        assertEquals(FRAMES, frames(out), "every frame exactly once");
        assertFalse(Files.exists(CompressRecovery.partFile(job.getCompress().getSpec())), "part removed");
        assertFalse(Files.exists(CompressRecovery.workDir(job.getCompress().getSpec())), "work dir removed");
    }

    @Test
    void compressesInSegmentsIntoOneFile() throws Exception {
        JobRecord job = newJob(2);
        JobQueue q = queue(new Noop());
        q.submit(List.of(job));
        assertTrue(q.awaitIdle(Duration.ofMinutes(3)));
        assertFinished(job);
        // 0-4, 4-8, 8-12, 12-20: a tail shorter than 5 s joins the last segment
        assertEquals(4, job.getCompress().getSegments().size());
    }

    @Test
    void stopNowThenResumeContinuesFromSegmentTable() throws Exception {
        JobRecord job = newJob(1);
        JobQueue[] holder = new JobQueue[1];
        boolean[] stopped = {false};
        holder[0] = queue(new Noop() {
            @Override
            public void progress(JobRecord j, Progress p) {
                // stop as soon as the first segment is in the growing output
                if (!stopped[0] && j.getCompress().getCommittedBytes() > 0) {
                    stopped[0] = true;
                    holder[0].stopNow();
                }
            }
        });
        holder[0].submit(List.of(job));
        assertTrue(holder[0].awaitIdle(Duration.ofMinutes(3)));
        assertEquals(JobState.PAUSED, job.getState());
        long appended = job.getCompress().getSegments().stream().filter(s -> s.getState() == SegmentState.APPENDED).count();
        assertTrue(appended >= 1 && appended < 4, "partially done: " + appended);
        Path part = CompressRecovery.partFile(job.getCompress().getSpec());
        assertTrue(FragmentedMp4.inspect(part).samples() >= 120, "growing output is playable");

        // the job survives a reload of the project file
        Project reloaded = ProjectStore.load(projectFile);
        project = reloaded;
        JobRecord again = reloaded.getJobs().getFirst();
        JobQueue q2 = queue(new Noop());
        q2.resume();
        assertTrue(q2.awaitIdle(Duration.ofMinutes(3)));
        assertFinished(again);
    }

    @Test
    void recoversFromCrashDuringAppendAndEncoding() throws Exception {
        JobRecord job = newJob(1);
        JobQueue[] holder = new JobQueue[1];
        boolean[] stopped = {false};
        holder[0] = queue(new Noop() {
            @Override
            public void progress(JobRecord j, Progress p) {
                if (!stopped[0] && j.getCompress().getSegments().stream()
                        .filter(s -> s.getState() == SegmentState.APPENDED).count() >= 2) {
                    stopped[0] = true;
                    holder[0].stopNow();
                }
            }
        });
        holder[0].submit(List.of(job));
        assertTrue(holder[0].awaitIdle(Duration.ofMinutes(3)));

        // simulate a crash: half-written fragment at the end of the output, a segment marked ENCODING with a
        // truncated temp file, and the job still RUNNING in the project file
        CompressState st = job.getCompress();
        Path part = CompressRecovery.partFile(st.getSpec());
        Files.write(part, new byte[]{0, 0, 0, 40, 'm', 'o', 'o', 'f', 0, 0, 0}, StandardOpenOption.APPEND);
        SegmentRecord pending = st.getSegments().stream().filter(s -> s.getState() == SegmentState.PENDING)
                .findFirst().orElseThrow();
        pending.setState(SegmentState.ENCODING);
        Files.writeString(CompressRecovery.segmentFile(st.getSpec(), pending), "partial");
        job.setState(JobState.RUNNING);
        ProjectStore.save(project, projectFile);

        Project reloaded = ProjectStore.load(projectFile);
        project = reloaded;
        JobRecord again = reloaded.getJobs().getFirst();
        List<RecoveryService.Issue> issues = RecoveryService.analyze(reloaded);
        assertEquals(RecoveryService.Kind.INTERRUPTED, issues.getFirst().kind());
        RecoveryService.prepareResume(again, Clock.systemUTC().instant());
        assertEquals(JobState.PENDING, again.getState());
        assertTrue(Files.exists(part), "growing output is kept for compression jobs");

        JobQueue q2 = queue(new Noop());
        q2.start();
        assertTrue(q2.awaitIdle(Duration.ofMinutes(3)));
        assertFinished(again);
    }

    @Test
    void sampleEstimatesSize() throws Exception {
        JobRecord job = newJob(1);
        CompressSampler.Result r = new CompressSampler(tools).sample(job.getCompress().getSpec(), 6, null);
        assertEquals(6, r.sampleSeconds(), 0.01);
        assertTrue(r.sampleBytes() > 0 && r.estimatedBytes() > r.sampleBytes());
        assertTrue(r.speed() > 0);
    }

    private static class Noop implements JobQueue.Listener {
        @Override
        public void jobChanged(JobRecord job) {
        }

        @Override
        public void progress(JobRecord job, Progress progress) {
        }

        @Override
        public void idle() {
        }
    }
}
