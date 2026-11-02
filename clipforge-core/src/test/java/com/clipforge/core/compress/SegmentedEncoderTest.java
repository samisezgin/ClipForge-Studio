package com.clipforge.core.compress;

import com.clipforge.core.FfmpegTestSupport;
import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicSettings;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.job.JobExecutor;
import com.clipforge.core.job.JobQueue;
import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.mp4.FragmentedMp4;
import com.clipforge.core.project.Fingerprint;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.JobType;
import com.clipforge.core.project.Project;
import com.clipforge.core.project.ProjectStore;
import com.clipforge.core.project.RecoveryService;
import com.clipforge.core.project.SourceDeletion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

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
    void speedsUpVideoAndAudioKeepingTheFrameRate() throws Exception {
        MediaInfo m = new ProbeService(tools).probe(source).orElseThrow();
        CompressOptions o = new CompressOptions(profile, profile.defaultQuality(), 4, 2, AudioMode.KEEP).withSpeed(2.5);
        CompressSpec spec = CompressPlanner.forFile(source, Files.size(source), m,
                Files.createDirectories(tmp.resolve("çıktı")), o, List.of());
        assertTrue(spec.output().endsWith("_av1_2.5x.mp4"), spec.output());
        JobRecord job = JobRecord.compress(spec, List.of(), Clock.systemUTC().instant());
        JobQueue q = queue(new Noop());
        q.submit(List.of(job));
        assertTrue(q.awaitIdle(Duration.ofMinutes(3)));
        assertEquals(JobState.DONE, job.getState(), String.valueOf(job.getErrorDetail()));
        Path out = Path.of(job.output());
        MediaInfo r = new ProbeService(tools).probe(out).orElseThrow();
        assertEquals(8.0, r.duration().toMillis() / 1000.0, 0.3, "20 s at 2.5x");
        assertTrue(r.hasAudio(), "sped-up audio kept");
        assertEquals(30, r.frameRate(), 0.5, "source frame rate kept");
        assertEquals(240, frames(out), 4, "one frame per 2.5 source frames");
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

    private List<Path> twoClips(String folder) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(folder));
        Path a = FfmpegTestSupport.makeClip(tools, dir.resolve("a.mp4"), 6, "320x180");
        Path b = FfmpegTestSupport.makeClip(tools, dir.resolve("b.mp4"), 6, "320x180");
        return List.of(a, b);
    }

    private JobRecord jobFor(List<Path> clips, SourceDeletion deletion, String outName) throws Exception {
        ProbeService probe = new ProbeService(tools);
        List<CompressInput> inputs = new ArrayList<>();
        List<Fingerprint> fps = new ArrayList<>();
        long ms = 0;
        long bytes = 0;
        for (Path c : clips) {
            MediaInfo m = probe.probe(c).orElseThrow();
            inputs.add(CompressInput.of(c, m));
            fps.add(Fingerprint.of(c));
            ms += m.duration().toMillis();
            bytes += Files.size(c);
        }
        CompressOptions o = new CompressOptions(profile, profile.defaultQuality(), 2, 1, AudioMode.KEEP);
        Path out = Files.createDirectories(tmp.resolve("out-" + outName)).resolve(outName + ".mp4");
        JobRecord job = JobRecord.compress(new CompressSpec(inputs, out.toString(), o, ms, bytes, true), fps,
                Clock.systemUTC().instant());
        job.setSourceDeletion(deletion);
        return job;
    }

    @Test
    void deletesEachSourceDuringTheWorkOnceItIsInTheOutput() throws Exception {
        List<Path> clips = twoClips("delete-during");
        JobRecord job = jobFor(clips, new SourceDeletion(SourceDeletion.When.DURING, true), "during");
        List<String> order = new CopyOnWriteArrayList<>();
        JobQueue q = new JobQueue(project, Map.<JobType, JobExecutor>of(JobType.COMPRESS, new SegmentedEncoder(tools)),
                () -> { }, new Noop(), Clock.systemUTC(), (file, permanent) -> {
                    // when the first clip goes, the second one is still being encoded
                    order.add(file.getFileName() + (job.getState() == JobState.RUNNING ? ":running" : ":after")
                            + (permanent ? ":permanent" : ":bin") + ":" + Files.exists(clips.get(1)));
                    Files.delete(file);
                    return true;
                });
        q.submit(List.of(job));
        assertTrue(q.awaitIdle(Duration.ofMinutes(3)));
        assertEquals(JobState.DONE, job.getState(), String.valueOf(job.getErrorDetail()));
        assertEquals("a.mp4:running:permanent:true", order.get(0), String.valueOf(order));
        assertEquals(2, order.size(), String.valueOf(order));
        assertTrue(clips.stream().noneMatch(Files::exists), "both sources deleted");
        assertEquals(2, job.getDeletedSources().size());
        assertEquals(360, frames(Path.of(job.output())), "output complete although the sources are gone");
    }

    @Test
    void deletesAfterTheJobButKeepsSourcesOtherJobsNeed() throws Exception {
        List<Path> clips = twoClips("delete-after");
        JobRecord job = jobFor(clips, new SourceDeletion(SourceDeletion.When.AFTER, false), "after");
        // another (paused) job still needs the second clip
        JobRecord other = jobFor(List.of(clips.get(1)), null, "other");
        other.setState(JobState.PAUSED);
        project.getJobs().add(other);
        List<String> removed = new CopyOnWriteArrayList<>();
        JobQueue q = new JobQueue(project, Map.<JobType, JobExecutor>of(JobType.COMPRESS, new SegmentedEncoder(tools)),
                () -> { }, new Noop(), Clock.systemUTC(), (file, permanent) -> {
                    assertFalse(permanent, "Recycle Bin by default");
                    assertTrue(Files.exists(Path.of(job.output())), "output finished before deleting");
                    removed.add(file.getFileName().toString());
                    Files.delete(file);
                    return true;
                });
        q.submit(List.of(job));
        assertTrue(q.awaitIdle(Duration.ofMinutes(3)));
        assertEquals(JobState.DONE, job.getState(), String.valueOf(job.getErrorDetail()));
        assertEquals(List.of("a.mp4"), removed);
        assertTrue(Files.exists(clips.get(1)), "kept: the paused job needs it");
        assertEquals(List.of(clips.get(1).toAbsolutePath().toString()), job.getKeptSources());
    }

    /** Source with a 1.4 s start offset and B-frames, plus a second clip: the reviewers' failure cases. */
    @Test
    void offsetSourcesAndMultipleClipsStayFrameExact() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("offset"));
        Path a = dir.resolve("a.mp4");
        Path b = dir.resolve("b.mp4");
        for (Path f : List.of(a, b)) {
            FfmpegTestSupport.run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30000/1001:duration=11",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=11",
                    "-c:v", "libx264", "-preset", "ultrafast", "-bf", "3", "-g", "60", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-output_ts_offset", "1.4", f.toString()));
        }
        ProbeService probe = new ProbeService(tools);
        MediaInfo ma = probe.probe(a).orElseThrow();
        assertEquals(1.4, ma.videoStart().toMillis() / 1000.0, 0.01, "offset is detected");
        MediaInfo mb = probe.probe(b).orElseThrow();
        List<CompressInput> sources = List.of(CompressInput.of(a, ma), CompressInput.of(b, mb));
        long ms = ma.duration().toMillis() + mb.duration().toMillis();
        CompressOptions o = new CompressOptions(profile, profile.defaultQuality(), 4, 2, AudioMode.KEEP);
        Path out = Files.createDirectories(tmp.resolve("o2")).resolve("joined.mp4");
        JobRecord job = JobRecord.compress(new CompressSpec(sources, out.toString(), o, ms,
                Files.size(a) + Files.size(b), true), List.of(), Clock.systemUTC().instant());
        JobQueue q = queue(new Noop());
        q.submit(List.of(job));
        assertTrue(q.awaitIdle(Duration.ofMinutes(3)));
        assertEquals(JobState.DONE, job.getState(), String.valueOf(job.getErrorDetail()));
        assertTrue(job.getCompress().getSegments().stream()
                .allMatch(s -> s.getLocalStartMs() + s.durationMs() <= sources.get(s.getInput()).durationMs()),
                "segments never cross sources");
        int expected = frames(a) + frames(b);
        assertEquals(expected, frames(out), "every frame of both clips exactly once");
        MediaInfo m = probe.probe(out).orElseThrow();
        assertEquals(ms / 1000.0, m.duration().toMillis() / 1000.0, 0.5);
    }

    @Test
    void cancellingAPausedJobRemovesItsFiles() throws Exception {
        JobRecord job = newJob(1);
        JobQueue[] holder = new JobQueue[1];
        boolean[] stopped = {false};
        holder[0] = queue(new Noop() {
            @Override
            public void progress(JobRecord j, Progress p) {
                if (!stopped[0] && j.getCompress().getCommittedBytes() > 0) {
                    stopped[0] = true;
                    holder[0].stopNow();
                }
            }
        });
        holder[0].submit(List.of(job));
        assertTrue(holder[0].awaitIdle(Duration.ofMinutes(3)));
        assertEquals(JobState.PAUSED, job.getState());
        CompressSpec spec = job.getCompress().getSpec();
        assertTrue(Files.exists(CompressRecovery.partFile(spec)));
        holder[0].cancel(job);
        assertEquals(JobState.CANCELLED, job.getState());
        assertFalse(Files.exists(CompressRecovery.partFile(spec)), "growing output removed");
        assertFalse(Files.exists(CompressRecovery.workDir(spec)), "work folder removed");
    }

    @Test
    void damagedOutputKeepsIntactSegments() throws Exception {
        JobRecord job = newJob(1);
        JobQueue[] holder = new JobQueue[1];
        boolean[] stopped = {false};
        holder[0] = queue(new Noop() {
            @Override
            public void progress(JobRecord j, Progress p) {
                if (!stopped[0] && j.getCompress().getSegments().stream()
                        .filter(s -> s.getState() == SegmentState.APPENDED).count() >= 3) {
                    stopped[0] = true;
                    holder[0].stopNow();
                }
            }
        });
        holder[0].submit(List.of(job));
        assertTrue(holder[0].awaitIdle(Duration.ofMinutes(3)));
        CompressState st = job.getCompress();
        Path part = CompressRecovery.partFile(st.getSpec());
        // cut the file in the middle of the third segment (e.g. a sync tool truncated it)
        FragmentedMp4.Info info = FragmentedMp4.inspect(part);
        long cut = info.fragments().getLast().mdat().offset() + 10;
        FragmentedMp4.truncate(part, cut);
        synchronized (project) {
            CompressRecovery.reconcile(st);
        }
        long kept = st.getSegments().stream().filter(s -> s.getState() == SegmentState.APPENDED).count();
        assertTrue(kept >= 1 && kept < 3, "intact prefix kept: " + kept);
        assertEquals(st.getCommittedBytes(), Files.size(part));

        JobQueue q2 = queue(new Noop());
        q2.resume();
        assertTrue(q2.awaitIdle(Duration.ofMinutes(3)));
        assertFinished(job);
    }

    @Test
    void compressWithLoopingMusicAndWithoutAudio() throws Exception {
        Path tune = tmp.resolve("tune.mp3");
        FfmpegTestSupport.run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=600:duration=3", "-c:a", "libmp3lame", tune.toString()));
        MediaInfo src = new ProbeService(tools).probe(source).orElseThrow();
        Path outDir = Files.createDirectories(tmp.resolve("music-out"));
        var music = new MusicSettings(List.of(tune.toString()), true, 0.5, true, 2, 1.0,
                true, 0.2);
        CompressOptions withMusic = new CompressOptions(profile, profile.defaultQuality(), 4, 2,
                AudioMode.MUSIC, music);
        CompressOptions silent = new CompressOptions(profile, profile.defaultQuality(), 4, 2, AudioMode.REMOVE);
        JobRecord a = JobRecord.compress(CompressPlanner.forFile(source, Files.size(source), src, outDir, withMusic,
                List.of()), List.of(), Clock.systemUTC().instant());
        JobRecord b = JobRecord.compress(CompressPlanner.forFile(source, Files.size(source), src, outDir, silent,
                List.of(Path.of(a.output()))), List.of(), Clock.systemUTC().instant());
        JobQueue q = queue(new Noop());
        q.submit(List.of(a, b));
        assertTrue(q.awaitIdle(Duration.ofMinutes(3)));
        assertEquals(JobState.DONE, a.getState(), String.valueOf(a.getErrorDetail()));
        assertEquals(JobState.DONE, b.getState(), String.valueOf(b.getErrorDetail()));
        MediaInfo ma = new ProbeService(tools).probe(Path.of(a.output())).orElseThrow();
        assertEquals("aac", ma.audioCodec(), "music track");
        assertEquals(20.0, ma.duration().toMillis() / 1000.0, 0.5);
        MediaInfo mb = new ProbeService(tools).probe(Path.of(b.output())).orElseThrow();
        assertFalse(mb.hasAudio(), "audio removed");
        assertEquals(FRAMES, frames(Path.of(b.output())));
    }

    @Test
    void burnsRecordingTimeIntoThePicture() throws Exception {
        assumeTrue(TimeOverlay.font().isPresent(), "no font available");
        MediaInfo m = new ProbeService(tools).probe(source).orElseThrow();
        long start = LocalDateTime.of(2026, 11, 1, 23, 39, 33)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        CompressOptions o = new CompressOptions(profile, profile.defaultQuality(), 4, 2, AudioMode.REMOVE,
                MusicSettings.DEFAULT, true);
        Path outDir = Files.createDirectories(tmp.resolve("overlay"));
        CompressSpec spec = new CompressSpec(List.of(CompressInput.of(source, m, start)),
                outDir.resolve("stamped.mp4").toString(), o, m.duration().toMillis(), Files.size(source), false);
        JobRecord job = JobRecord.compress(spec, List.of(), Clock.systemUTC().instant());
        JobQueue q = queue(new Noop());
        q.submit(List.of(job));
        assertTrue(q.awaitIdle(Duration.ofMinutes(3)));
        assertEquals(JobState.DONE, job.getState(), String.valueOf(job.getErrorDetail()));
        assertEquals(FRAMES, frames(Path.of(job.output())));
        // keep a frame 12 s in (third segment) for visual inspection: it must read 2026-11-01 23:39:45
        Path frame = Path.of(System.getProperty("user.dir")).getParent().resolve("test-output").resolve("overlay-12s.png");
        Files.createDirectories(frame.getParent());
        FfmpegTestSupport.run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y", "-ss", "12", "-i", job.output(),
                "-frames:v", "1", frame.toString()));
        assertTrue(Files.size(frame) > 0);
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
