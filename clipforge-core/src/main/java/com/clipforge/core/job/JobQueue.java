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
 * {@code persist} callback, so the project file always reflects the latest state.
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

    /** Adds jobs to the project and starts the worker if it is not running and not paused. */
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
            if (pauseRequested || (worker != null && worker.isAlive())) {
                return;
            }
            idleLatch = new CountDownLatch(1);
            worker = Thread.ofPlatform().name("clipforge-jobs").daemon(true).start(this::loop);
        }
    }

    public boolean isRunning() {
        Thread w = worker;
        return w != null && w.isAlive();
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

    /** Clears the pause and continues with paused/pending jobs. */
    public void resume() {
        pauseRequested = false;
        synchronized (project) {
            for (JobRecord j : project.getJobs()) {
                if (j.getState() == JobState.PAUSED) {
                    update(j, JobState.PENDING);
                }
            }
        }
        persist.run();
        start();
    }

    /** Cancels one job: running → stopped and output removed; pending/paused → cancelled. */
    public void cancel(JobRecord job) {
        if (job == current) {
            stopMode = StopMode.CANCEL_JOB;
            CancellationToken t = currentToken;
            if (t != null) {
                t.cancel();
            }
            return;
        }
        synchronized (project) {
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

    private void loop() {
        try {
            while (!pauseRequested) {
                JobRecord next;
                synchronized (project) {
                    next = project.getJobs().stream()
                            .filter(j -> j.getState() == JobState.PENDING).findFirst().orElse(null);
                }
                if (next == null) {
                    break;
                }
                runJob(next);
            }
        } finally {
            current = null;
            currentToken = null;
            idleLatch.countDown();
            listener.idle();
        }
    }

    private void runJob(JobRecord job) {
        current = job;
        stopMode = StopMode.NONE;
        CancellationToken token = new CancellationToken();
        currentToken = token;
        synchronized (project) {
            job.clearError();
            update(job, JobState.RUNNING);
        }
        persist.run();
        try {
            MergeSpec spec = job.getMerge();
            Path out = Path.of(spec.output());
            Files.createDirectories(out.toAbsolutePath().getParent());
            DiskCheck.Result disk = DiskCheck.check(out.toAbsolutePath().getParent(), spec.totalBytes(),
                    spec.totalBytes());
            if (!disk.enoughSpace()) {
                finishFailed(job, "error.diskSpace", null,
                        Formats.size(disk.requiredBytes()), Formats.size(disk.usableBytes()));
                return;
            }
            if (disk.exceedsFileSizeLimit()) {
                finishFailed(job, "error.fat32", null, disk.fileSystem());
                return;
            }
            FfmpegResult r = merge.merge(spec, p -> listener.progress(job, p), token);
            synchronized (project) {
                if (r.success() && Files.isRegularFile(out)) {
                    update(job, JobState.DONE);
                } else if (r.cancelled()) {
                    update(job, stopMode == StopMode.STOP_NOW ? JobState.PAUSED : JobState.CANCELLED);
                } else {
                    job.fail("error.ffmpeg", tail(r.stderrTail()), String.valueOf(r.exitCode()));
                    job.setUpdated(clock.instant());
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Job failed", e);
            finishFailed(job, "error.io", null, String.valueOf(e.getMessage()));
            return;
        }
        persist.run();
        listener.jobChanged(job);
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
