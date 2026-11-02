package com.clipforge.core.job;

import com.clipforge.core.FfmpegTestSupport;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.merge.MergeOptions;
import com.clipforge.core.merge.MergeService;
import com.clipforge.core.merge.MergeSpec;
import com.clipforge.core.project.Fingerprint;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.Project;
import com.clipforge.core.project.ProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobQueueTest {

    @TempDir
    Path tmp;

    private JobRecord job(Path in, String outName) throws Exception {
        MergeSpec spec = new MergeSpec(List.of(in.toString()), tmp.resolve(outName).toString(),
                MergeOptions.DEFAULT, 2000, Files.size(in));
        return JobRecord.merge(spec, List.of(Fingerprint.of(in)), Clock.systemUTC().instant());
    }

    @Test
    void runsJobsInOrderAndPersistsEveryStateChange() throws Exception {
        FfmpegTools tools = FfmpegTestSupport.requireFfmpeg();
        Path in = FfmpegTestSupport.makeClip(tools, tmp.resolve("a.mp4"), 2, "160x90");
        Project project = new Project();
        Path file = tmp.resolve("p.cfproj");
        AtomicInteger saves = new AtomicInteger();
        JobQueue q = new JobQueue(project, new MergeService(tools), () -> {
            synchronized (project) {
                try {
                    ProjectStore.save(project, file);
                    saves.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }, new NoopListener(), Clock.systemUTC());

        JobRecord a = job(in, "a-out.mp4");
        JobRecord b = job(in, "b-out.mp4");
        q.submit(List.of(a, b));
        assertTrue(q.awaitIdle(Duration.ofSeconds(60)));

        assertEquals(JobState.DONE, a.getState());
        assertEquals(JobState.DONE, b.getState());
        assertTrue(saves.get() >= 5, "saved on submit and on every transition");
        Project reloaded = ProjectStore.load(file);
        assertTrue(reloaded.getJobs().stream().allMatch(j -> j.getState() == JobState.DONE));
    }

    @Test
    void pauseKeepsPendingJobsAndResumeRunsThem() throws Exception {
        FfmpegTools tools = FfmpegTestSupport.requireFfmpeg();
        Path in = FfmpegTestSupport.makeClip(tools, tmp.resolve("a.mp4"), 2, "160x90");
        Project project = new Project();
        JobQueue q = new JobQueue(project, new MergeService(tools), () -> { }, new NoopListener(),
                Clock.systemUTC());
        q.pause();
        JobRecord a = job(in, "a-out.mp4");
        q.submit(List.of(a));
        assertEquals(JobState.PENDING, a.getState(), "a paused queue does not start new jobs");
        q.resume();
        assertTrue(q.awaitIdle(Duration.ofSeconds(60)));
        assertEquals(JobState.DONE, a.getState());
    }

    @Test
    void failingJobRecordsErrorKey() throws Exception {
        FfmpegTools tools = FfmpegTestSupport.requireFfmpeg();
        Path notVideo = Files.writeString(tmp.resolve("x.mp4"), "not a video");
        Project project = new Project();
        JobQueue q = new JobQueue(project, new MergeService(tools), () -> { }, new NoopListener(),
                Clock.systemUTC());
        JobRecord a = job(notVideo, "x-out.mp4");
        q.submit(List.of(a));
        assertTrue(q.awaitIdle(Duration.ofSeconds(60)));
        assertEquals(JobState.FAILED, a.getState());
        assertEquals("error.ffmpeg", a.getErrorKey());
        assertTrue(Files.notExists(tmp.resolve("x-out.mp4")));
    }

    private static final class NoopListener implements JobQueue.Listener {
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
