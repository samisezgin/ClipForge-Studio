package com.clipforge.core.project;

import com.clipforge.core.merge.MergeMode;
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

class ProjectStoreTest {

    @TempDir
    Path dir;

    @Test
    void roundTripKeepsEverything() throws Exception {
        Path src = Files.writeString(dir.resolve("kayıt ş.mp4"), "x");
        Project p = new Project();
        p.getFolders().add(dir.toString());
        p.getSelectedClips().add(src.toString());
        p.setMergeMode(MergeMode.ALL_IN_ONE);
        p.setMergeOptions(new MergeOptions(true));
        p.setOutputDir(dir.resolve("out").toString());
        MergeSpec spec = new MergeSpec(List.of(src.toString()), dir.resolve("o.mp4").toString(),
                MergeOptions.DEFAULT, 1000, 1);
        JobRecord job = JobRecord.merge(spec, List.of(Fingerprint.of(src)), Instant.parse("2026-11-02T10:00:00Z"));
        job.fail("error.ffmpeg", "tail", "1");
        p.getJobs().add(job);

        Path file = dir.resolve("p.cfproj");
        ProjectStore.save(p, file);
        assertFalse(Files.exists(dir.resolve("p.cfproj.tmp")), "temp file is moved into place");

        Project back = ProjectStore.load(file);
        assertEquals(p.getFolders(), back.getFolders());
        assertEquals(p.getSelectedClips(), back.getSelectedClips());
        assertEquals(MergeMode.ALL_IN_ONE, back.getMergeMode());
        assertTrue(back.getMergeOptions().faststart());
        JobRecord j = back.getJobs().getFirst();
        assertEquals(job.getId(), j.getId());
        assertEquals(JobState.FAILED, j.getState());
        assertEquals(List.of("1"), j.getErrorArgs());
        assertEquals(spec, j.getMerge());
        assertEquals(job.getCreated(), j.getCreated());
        assertTrue(j.getSources().getFirst().matchesDisk());
    }

    @Test
    void addsExtension() {
        assertEquals("a.cfproj", ProjectStore.withExtension(Path.of("a")).getFileName().toString());
        assertEquals("a.cfproj", ProjectStore.withExtension(Path.of("a.cfproj")).getFileName().toString());
    }
}
