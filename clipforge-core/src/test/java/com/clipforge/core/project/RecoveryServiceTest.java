package com.clipforge.core.project;

import com.clipforge.core.merge.MergeOptions;
import com.clipforge.core.merge.MergeSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @TempDir
    Path dir;

    private JobRecord job(Path src, Path out, JobState state) throws Exception {
        MergeSpec spec = new MergeSpec(List.of(src.toString()), out.toString(), MergeOptions.DEFAULT, 1000, 1);
        JobRecord j = JobRecord.merge(spec, List.of(Fingerprint.of(src)), NOW);
        j.setState(state);
        return j;
    }

    @Test
    void interruptedRunningJobLosesPartFileAndBecomesPending() throws Exception {
        Path src = Files.writeString(dir.resolve("a.mp4"), "video");
        Path out = dir.resolve("out.mp4");
        Path part = Files.writeString(dir.resolve("out.part.mp4"), "partial");
        Project p = new Project();
        JobRecord j = job(src, out, JobState.RUNNING);
        p.getJobs().add(j);

        List<RecoveryService.Issue> issues = RecoveryService.analyze(p);
        assertEquals(1, issues.size());
        assertEquals(RecoveryService.Kind.INTERRUPTED, issues.getFirst().kind());

        RecoveryService.prepareResume(j, NOW);
        assertEquals(JobState.PENDING, j.getState());
        assertFalse(Files.exists(part));
    }

    @Test
    void finishedButUnsavedJobIsMarkedDoneNotRedone() throws Exception {
        Path src = Files.writeString(dir.resolve("a.mp4"), "video");
        Path out = Files.writeString(dir.resolve("out.mp4"), "complete merge");
        JobRecord j = job(src, out, JobState.RUNNING);
        RecoveryService.prepareResume(j, NOW);
        assertEquals(JobState.DONE, j.getState());
        assertTrue(Files.exists(out), "a finished output is never deleted");
    }

    @Test
    void finalOutputIsNeverDeleted() throws Exception {
        Path src = Files.writeString(dir.resolve("a.mp4"), "video");
        Path unrelated = Files.writeString(dir.resolve("out.mp4"), "user file");
        Files.writeString(dir.resolve("out.part.mp4"), "partial");
        for (JobState s : List.of(JobState.RUNNING, JobState.PAUSED, JobState.PENDING)) {
            RecoveryService.prepareResume(job(src, unrelated, s), NOW);
            assertTrue(Files.exists(unrelated), s.name());
        }
    }

    @Test
    void changedSourceIsReported() throws Exception {
        Path src = Files.writeString(dir.resolve("a.mp4"), "video");
        Project p = new Project();
        p.getJobs().add(job(src, dir.resolve("o.mp4"), JobState.PENDING));
        Files.writeString(src, "video grew while copying");

        RecoveryService.Issue issue = RecoveryService.analyze(p).getFirst();
        assertEquals(RecoveryService.Kind.SOURCE_CHANGED, issue.kind());
        assertEquals(List.of(src.toString()), issue.changedSources());

        RecoveryService.acceptChangedSources(issue.job());
        assertEquals(RecoveryService.Kind.INTERRUPTED, RecoveryService.analyze(p).getFirst().kind());
    }

    @Test
    void missingOutputOfFinishedJobIsInformational() throws Exception {
        Path src = Files.writeString(dir.resolve("a.mp4"), "video");
        Project p = new Project();
        p.getJobs().add(job(src, dir.resolve("gone.mp4"), JobState.DONE));
        assertEquals(RecoveryService.Kind.OUTPUT_MISSING, RecoveryService.analyze(p).getFirst().kind());
    }
}
