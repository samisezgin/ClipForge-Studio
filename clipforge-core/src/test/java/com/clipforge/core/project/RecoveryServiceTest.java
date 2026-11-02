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
    void interruptedRunningJobLosesPartialOutputAndBecomesPending() throws Exception {
        Path src = Files.writeString(dir.resolve("a.mp4"), "video");
        Path out = Files.writeString(dir.resolve("out.mp4"), "partial");
        Project p = new Project();
        JobRecord j = job(src, out, JobState.RUNNING);
        p.getJobs().add(j);

        List<RecoveryService.Issue> issues = RecoveryService.analyze(p);
        assertEquals(1, issues.size());
        assertEquals(RecoveryService.Kind.INTERRUPTED, issues.getFirst().kind());

        RecoveryService.prepareResume(j, NOW);
        assertEquals(JobState.PENDING, j.getState());
        assertFalse(Files.exists(out));
    }

    @Test
    void pausedJobKeepsExistingFileUntouched() throws Exception {
        Path src = Files.writeString(dir.resolve("a.mp4"), "video");
        Path unrelated = Files.writeString(dir.resolve("out.mp4"), "user file");
        JobRecord j = job(src, unrelated, JobState.PAUSED);
        RecoveryService.prepareResume(j, NOW);
        assertTrue(Files.exists(unrelated), "only RUNNING jobs own a partial output");
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
