package com.clipforge.core.job;

import com.clipforge.core.mp4.Mp4Reader;
import com.clipforge.core.project.Fingerprint;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.Project;
import com.clipforge.core.project.SourceDeletion;
import com.clipforge.core.util.RecycleBin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Deletes a job's source files when the user asked for it. A source is kept when another unfinished job still needs
 * it or when it changed since the job was created (then it is not the file the user chose to delete). Every deletion
 * is logged and recorded in the job, so a deleted source is never checked or expected again.
 */
public final class SourceCleanup {

    private static final Logger LOG = Logger.getLogger(SourceCleanup.class.getName());

    /** Removes one file; tests replace the Recycle Bin. */
    public interface Remover {
        /** @return {@code true} if the file is gone */
        boolean remove(Path file, boolean permanent) throws IOException;
    }

    /** Recycle Bin by default, {@code Files.delete} when permanent. */
    public static final Remover SYSTEM = (file, permanent) -> {
        if (permanent) {
            Files.deleteIfExists(file);
            return true;
        }
        return RecycleBin.moveToRecycleBin(file);
    };

    private final Project project;
    private final Remover remover;
    private final Runnable persist;

    /** @param persist saves the project (a deletion is recorded before the file is removed) */
    public SourceCleanup(Project project, Remover remover, Runnable persist) {
        this.project = project;
        this.remover = remover;
        this.persist = persist;
    }

    /**
     * Comparable form of a path: the same file written as {@code C:\a.mp4} and {@code c:\A.MP4} must count as one
     * (Windows file names are case-insensitive).
     */
    static String key(String path) {
        String p = Path.of(path).toAbsolutePath().normalize().toString();
        return WINDOWS ? p.toLowerCase(Locale.ROOT) : p;
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
            .startsWith("windows");

    /** After a job finished: deletes all its remaining sources, if its output is complete. */
    public void afterDone(JobRecord job) {
        SourceDeletion d = job.getSourceDeletion();
        if (d == null) {
            return;
        }
        if (!outputComplete(job)) {
            LOG.warning("Output of " + job.getId() + " could not be verified; sources are kept");
            synchronized (project) {
                job.getSources().forEach(f -> keep(job, f.path()));
            }
            return;
        }
        List<Fingerprint> sources;
        synchronized (project) {
            sources = List.copyOf(job.getSources());
        }
        for (Fingerprint f : sources) {
            delete(job, f);
        }
    }

    /** During the work: one source is no longer needed by the job. */
    public void sourceDone(JobRecord job, String path) {
        SourceDeletion d = job.getSourceDeletion();
        if (d == null || d.when() != SourceDeletion.When.DURING) {
            return;
        }
        Fingerprint f;
        synchronized (project) {
            f = job.getSources().stream().filter(s -> key(s.path()).equals(key(path))).findFirst().orElse(null);
        }
        if (f != null) {
            delete(job, f);
        }
    }

    private void delete(JobRecord job, Fingerprint f) {
        String path = f.path();
        synchronized (project) {
            if (job.getDeletedSources().contains(path) || job.getKeptSources().contains(path)) {
                return;
            }
            // every job that may still run needs its sources: pending, running, paused, and failed (can be retried)
            String k = key(path);
            boolean neededElsewhere = project.getJobs().stream().anyMatch(other -> other != job
                    && other.getState() != JobState.DONE && other.getState() != JobState.CANCELLED
                    && other.getSources().stream().anyMatch(s -> key(s.path()).equals(k)));
            if (neededElsewhere) {
                LOG.info("Kept " + path + ": another job still needs it");
                keep(job, path);
                return;
            }
        }
        if (!Files.exists(Path.of(path))) {
            synchronized (project) {
                job.getDeletedSources().add(path); // already gone (e.g. deleted by hand)
            }
            return;
        }
        if (!f.matchesDisk()) {
            LOG.warning("Kept " + path + ": it changed since the job was created");
            synchronized (project) {
                keep(job, path);
            }
            return;
        }
        boolean permanent = job.getSourceDeletion().permanent();
        // recorded and saved first: after a crash in between the file is at worst kept, never gone unrecorded
        synchronized (project) {
            job.getDeletedSources().add(path);
        }
        persist.run();
        boolean gone = false;
        try {
            gone = remover.remove(Path.of(path), permanent);
            LOG.info((gone ? (permanent ? "Deleted permanently: " : "Moved to the Recycle Bin: ") : "Kept: ") + path);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Could not delete " + path + ": " + e.getMessage());
        }
        if (!gone) {
            synchronized (project) {
                job.getDeletedSources().remove(path);
                keep(job, path);
            }
        }
    }

    private static void keep(JobRecord job, String path) {
        if (!job.getKeptSources().contains(path)) {
            job.getKeptSources().add(path);
        }
    }

    /** The output exists, is not empty and ends on a complete MP4 box. */
    private static boolean outputComplete(JobRecord job) {
        String out = job.output();
        if (out == null) {
            return false;
        }
        Path p = Path.of(out);
        try {
            return Files.isRegularFile(p) && Files.size(p) > 0 && !Mp4Reader.scan(p).truncated();
        } catch (IOException e) {
            return false;
        }
    }
}
