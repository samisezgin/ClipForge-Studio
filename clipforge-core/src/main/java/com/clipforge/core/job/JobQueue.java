package com.clipforge.core.job;

import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.FfmpegResult;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.merge.MergeService;
import com.clipforge.core.merge.MergeSpec;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.Project;
import com.clipforge.core.util.DiskCheck;
import com.clipforge.core.util.Formats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the project's jobs one after another on a background thread.
 * <p>
 * All mutations of {@link JobRecord}s happen while holding the project's monitor, followed by a save through the
 * {@code persist} callback, so the project file always reflects the latest state. Picking the next job and marking
 * it RUNNING is one atomic step, so a pause or cancel can never be overwritten by a job that is just starting.
 */
public final class JobQueue {

    /** Receives job updates on the worker thread; UI code must hop to its own thread. */
    public interface Listener {
        void jobChanged(JobRecord job);

        void progress(JobRecord job, Progress progress);

        /** The worker stopped: no more pending jobs, or paused. */
        void idle();
    }

    private enum StopMode { NONE, CANCEL_JOB, STOP_NOW }

    private static final Logger LOG = Logger.getLogger(JobQueue.class.getName());

    private final Project project;
    private final MergeService merge;
    private final Runnable persist;
    private final Listener listener;
    private final Clock clock;

    /** Guards {@link #worker}; never held while waiting for the project monitor. */
    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean pauseRequested;
    private volatile JobRecord current;
    private volatile CancellationToken currentToken;
    private volatile StopMode stopMode = StopMode.NONE;
    private volatile CountDownLatch idleLatch = new CountDownLatch(0);

    public JobQueue(Project project, MergeService merge, Runnable persist, Listener listener, Clock clock) {
        this.project = project;
        this.merge = merge;
        this.persist = persist;
        this.listener = listener;
        this.clock = clock;
    }

    /** Adds jobs to the project and starts the worker if it is not paused. */
    public void submit(List<JobRecord> jobs) {
        synchronized (project) {
            project.getJobs().addAll(jobs);
        }
        persist.run();
        jobs.forEach(listener::jobChanged);
        start();
    }

    /** Starts processing pending jobs unless paused. */
    public void start() {
        synchronized (lock) {
            if (pauseRequested || worker != null) {
                return;
            }
            CountDownLatch latch = new CountDownLatch(1);
            idleLatch = latch;
            worker = Thread.ofPlatform().name("clipforge-jobs").daemon(true).start(() -> loop(latch));
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return worker != null;
        }
    }

    public boolean isPaused() {
        return pauseRequested;
    }

    public JobRecord current() {
        return current;
    }

    /** Lossless pause: the running job finishes, no further jobs are started; pending jobs become PAUSED. */
    public void pause() {
        pauseRequested = true;
        synchronized (project) {
            for (JobRecord j : project.getJobs()) {
                if (j.getState() == JobState.PENDING) {
                    update(j, JobState.PAUSED);
                }
            }
        }
        persist.run();
        if (!isRunning()) {
            listener.idle();
        }
    }

    /** Stops immediately: the running job is interrupted, its partial output removed, and it becomes PAUSED. */
    public void stopNow() {
        pause();
        stopMode = StopMode.STOP_NOW;
        CancellationToken t = currentToken;
        if (t != null) {
            t.cancel();
        }
    }

    /** Clears the pause and continues with all paused and pending jobs. */
    public void resume() {
        synchronized (project) {
            for (JobRecord j : project.getJobs()) {
                if (j.getState() == JobState.PAUSED) {
                    update(j, JobState.PENDING);
                }
            }
        }
        persist.run();
        unpause();
    }

    /** Clears the pause flag and runs PENDING jobs only; jobs the user left PAUSED stay paused. */
    public void unpause() {
        pauseRequested = false;
        start();
    }

    /** Cancels one job: running → stopped and partial output removed; pending/paused → cancelled. */
    public void cancel(JobRecord job) {
        synchronized (project) {
            if (job == current) {
                stopMode = StopMode.CANCEL_JOB;
                CancellationToken t = currentToken;
                if (t != null) {
                    t.cancel();
                }
                return;
            }
            if (job.getState().unfinished()) {
                update(job, JobState.CANCELLED);
            }
        }
        persist.run();
    }

    /** Waits until the worker stopped. */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        return idleLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void loop(CountDownLatch latch) {
        try {
            while (true) {
                JobRecord next = claimNext();
                if (next == null) {
                    // decide to exit under the lock, then re-check: a resume/submit racing with the exit
                    // must either see the worker alive (and rely on it) or start a new one
                    synchronized (lock) {
                        if (pauseRequested || !hasPending()) {
                            worker = null;
                            break;
                        }
                    }
                    continue;
                }
                runJob(next);
            }
        } finally {
            latch.countDown();
            boolean replaced;
            synchronized (lock) {
                if (worker == Thread.currentThread()) {
                    worker = null; // also after an unexpected Error
                }
                replaced = worker != null; // a new worker was started while this one was finishing
            }
            if (!replaced) {
                listener.idle();
            }
        }
    }

    private boolean hasPending() {
        synchronized (project) {
            return project.getJobs().stream().anyMatch(j -> j.getState() == JobState.PENDING);
        }
    }

    /** Atomically picks the first PENDING job and marks it RUNNING; {@code null} if paused or nothing to do. */
    private JobRecord claimNext() {
        JobRecord next;
        synchronized (project) {
            if (pauseRequested) {
                return null;
            }
            next = project.getJobs().stream().filter(j -> j.getState() == JobState.PENDING).findFirst().orElse(null);
            if (next == null) {
                return null;
            }
            stopMode = StopMode.NONE;
            currentToken = new CancellationToken();
            current = next;
            next.clearError();
            update(next, JobState.RUNNING);
        }
        persist.run();
        return next;
    }

    private void runJob(JobRecord job) {
        CancellationToken token = currentToken;
        try {
            MergeSpec spec = job.getMerge();
            Path out = Path.of(spec.output());
            Path dir = out.toAbsolutePath().getParent();
            DiskCheck.Result disk = DiskCheck.check(dir, spec.totalBytes(), spec.totalBytes());
            if (!disk.enoughSpace()) {
                finishFailed(job, "error.diskSpace", null,
                        Formats.size(disk.requiredBytes()), Formats.size(disk.usableBytes()));
                return;
            }
            if (disk.exceedsFileSizeLimit()) {
                finishFailed(job, "error.fat32", null, disk.fileSystem());
                return;
            }
            Files.createDirectories(dir);
            FfmpegResult r = merge.merge(spec, p -> listener.progress(job, p), token);
            synchronized (project) {
                if (r.success() && Files.isRegularFile(out)) {
                    update(job, JobState.DONE);
                } else if (r.cancelled()) {
                    update(job, stopMode == StopMode.STOP_NOW ? JobState.PAUSED : JobState.CANCELLED);
                } else {
                    job.fail("error.ffmpeg", tail(r.stderrText().lines().toList()), String.valueOf(r.exitCode()));
                    job.setUpdated(clock.instant());
                }
            }
            persist.run();
            listener.jobChanged(job);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Job failed", e);
            finishFailed(job, "error.io", null, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            // a broken record (e.g. hand-edited project) must not leave the job RUNNING or kill the worker
            LOG.log(Level.SEVERE, "Unexpected job failure", e);
            finishFailed(job, "error.internal", String.valueOf(e), e.getClass().getSimpleName());
        } finally {
            current = null;
            currentToken = null;
        }
    }

    private void finishFailed(JobRecord job, String key, String detail, String... args) {
        synchronized (project) {
            job.fail(key, detail, args);
            job.setUpdated(clock.instant());
        }
        persist.run();
        listener.jobChanged(job);
    }

    private void update(JobRecord job, JobState state) {
        job.setState(state);
        job.setUpdated(clock.instant());
        listener.jobChanged(job);
    }

    private static String tail(List<String> lines) {
        int from = Math.max(0, lines.size() - 8);
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
