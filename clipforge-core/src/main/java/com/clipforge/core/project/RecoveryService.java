package com.clipforge.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Inspects a loaded project for interrupted work and prepares it for resumption.
 * Merge jobs resume per output file: a partially written output is deleted and the job is redone.
 * (Segment-level recovery for compression jobs is described in docs/RECOVERY.md.)
 */
public final class RecoveryService {

    public enum Kind {
        /** The app stopped while the job was running or paused; it can be resumed. */
        INTERRUPTED,
        /** A source file changed or disappeared since the job was created. */
        SOURCE_CHANGED,
        /** A finished job's output no longer exists (informational). */
        OUTPUT_MISSING
    }

    public record Issue(JobRecord job, Kind kind, List<String> changedSources) {
    }

    private RecoveryService() {
    }

    public static List<Issue> analyze(Project project) {
        List<Issue> issues = new ArrayList<>();
        for (JobRecord job : project.getJobs()) {
            if (job.getState().unfinished()) {
                List<String> changed = job.getSources().stream()
                        .filter(f -> !f.matchesDisk()).map(Fingerprint::path).toList();
                issues.add(new Issue(job, changed.isEmpty() ? Kind.INTERRUPTED : Kind.SOURCE_CHANGED, changed));
            } else if (job.getState() == JobState.DONE && job.output() != null
                    && !Files.exists(Path.of(job.output()))) {
                issues.add(new Issue(job, Kind.OUTPUT_MISSING, List.of()));
            }
        }
        return issues;
    }

    /**
     * Makes an unfinished job runnable again: removes a partial output left by an interrupted run and sets the
     * state to {@link JobState#PENDING}. The output is only deleted for jobs that were running, because the merge
     * never overwrites existing files, so any file at that path was written by this job.
     */
    public static void prepareResume(JobRecord job, Instant now) throws IOException {
        if (!job.getState().unfinished()) {
            return;
        }
        if (job.getState() == JobState.RUNNING && job.output() != null) {
            Files.deleteIfExists(Path.of(job.output()));
        }
        job.clearError();
        job.setState(JobState.PENDING);
        job.setUpdated(now);
    }

    /** Re-reads the fingerprints of a job's sources, accepting their current version. */
    public static void acceptChangedSources(JobRecord job) throws IOException {
        List<Fingerprint> refreshed = new ArrayList<>();
        for (Fingerprint f : job.getSources()) {
            refreshed.add(Fingerprint.of(Path.of(f.path())));
        }
        job.setSources(refreshed);
    }
}
