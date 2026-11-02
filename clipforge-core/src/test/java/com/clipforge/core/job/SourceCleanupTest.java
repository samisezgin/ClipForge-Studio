package com.clipforge.core.job;

import com.clipforge.core.project.Fingerprint;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.Project;
import com.clipforge.core.project.SourceDeletion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceCleanupTest {

    @TempDir
    Path tmp;

    private JobRecord job(Project p, JobState state, SourceDeletion d, Path... files) throws Exception {
        JobRecord j = new JobRecord();
        j.setState(state);
        List<Fingerprint> fps = new ArrayList<>();
        for (Path f : files) {
            fps.add(Fingerprint.of(f));
        }
        j.setSources(fps);
        j.setSourceDeletion(d);
        p.getJobs().add(j);
        return j;
    }

    private static final SourceDeletion DURING = new SourceDeletion(SourceDeletion.When.DURING, true);

    @Test
    void recordsTheDeletionBeforeRemovingTheFile() throws Exception {
        Path a = Files.writeString(tmp.resolve("a.mp4"), "a");
        Project p = new Project();
        JobRecord j = job(p, JobState.RUNNING, DURING, a);
        int[] saves = {0};
        SourceCleanup c = new SourceCleanup(p, (file, permanent) -> {
            assertTrue(j.getDeletedSources().contains(a.toAbsolutePath().toString()), "recorded first");
            assertEquals(1, saves[0], "and saved first");
            Files.delete(file);
            return true;
        }, () -> saves[0]++);
        c.sourceDone(j, a.toAbsolutePath().toString());
        assertFalse(Files.exists(a));
        assertEquals(List.of(a.toAbsolutePath().toString()), j.getDeletedSources());
    }

    @Test
    void keepsFilesAFailedJobStillNeedsEvenWrittenDifferently() throws Exception {
        Path a = Files.writeString(tmp.resolve("clip.mp4"), "a");
        Project p = new Project();
        JobRecord j = job(p, JobState.RUNNING, DURING, a);
        JobRecord failed = job(p, JobState.FAILED, null, a);
        // the other job wrote the same file in another case (Windows paths are case-insensitive)
        String other = a.toAbsolutePath().toString();
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            other = other.toUpperCase(Locale.ROOT);
        }
        failed.setSources(List.of(new Fingerprint(other, Files.size(a), Files.getLastModifiedTime(a).toMillis())));
        SourceCleanup c = new SourceCleanup(p, (file, permanent) -> {
            throw new AssertionError("must not delete " + file);
        }, () -> { });
        c.sourceDone(j, a.toAbsolutePath().toString());
        assertTrue(Files.exists(a), "a failed job can be retried and needs it");
        assertEquals(List.of(a.toAbsolutePath().toString()), j.getKeptSources());
    }

    @Test
    void aRefusedOrFailedRemovalKeepsTheFileRecordedAsKept() throws Exception {
        Path a = Files.writeString(tmp.resolve("a.mp4"), "a");
        Project p = new Project();
        JobRecord j = job(p, JobState.RUNNING, DURING, a);
        new SourceCleanup(p, (file, permanent) -> false, () -> { }).sourceDone(j, a.toAbsolutePath().toString());
        assertTrue(Files.exists(a));
        assertTrue(j.getDeletedSources().isEmpty());
        assertEquals(1, j.getKeptSources().size());
    }
}
