package com.clipforge.core.job;

import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.merge.MergeService;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.JobType;
import com.clipforge.core.project.Project;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the project's jobs one after another on a background thread, delegating the actual work to a
 * {@link JobExecutor} per {@link JobType}.
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

    private static final Logger LOG = Logger.getLogger(JobQueue.class.getName());

    private final Project project;
    private final Map<JobType, JobExecutor> executors;
    private final Runnable persist;
    private final Listener listener;
    private final Clock clock;

    /** Guards {@link #worker}; never held while waiting for the project monitor except in the exit check. */
    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean pauseRequested;
    private volatile boolean stopNowRequested;
    private volatile boolean cancelRequested;
    private volatile JobRecord current;
    private final Set<CancellationToken> tokens = ConcurrentHashMap.newKeySet();
    private volatile CountDownLatch idleLatch = new CountDownLatch(0);

    public JobQueue(Project project, Map<JobType, JobExecutor> executors, Runnable persist, Listener listener,
                    Clock clock) {
        this.project = project;
        this.executors = new EnumMap<>(executors);
        this.persist = persist;
        this.listener = listener;
        this.clock = clock;
    }

    /** Merge-only queue (used by tests and simple callers). */
    public JobQueue(Project project, MergeService merge, Runnable persist, Listener listener, Clock clock) {
        this(project, Map.of(JobType.MERGE, new MergeExecutor(merge)), persist, listener, clock);
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

    /** Lossless pause: work in progress finishes, nothing new starts; pending jobs become PAUSED. */
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

    /** Stops immediately: running FFmpeg processes are interrupted and the running job becomes PAUSED. */
    public void stopNow() {
        pause();
        stopNowRequested = true;
        tokens.forEach(CancellationToken::cancel);
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

    /** Cancels one job: running → stopped and its partial files removed; pending/paused → cancelled. */
    public void cancel(JobRecord job) {
        synchronized (project) {
            if (job == current) {
                cancelRequested = true;
                tokens.forEach(CancellationToken::cancel);
                return;
            }
            if (job.getState().unfinished()) {
                update(job, JobState.CANCELLED);
            }
        }
        persist.run();
        JobExecutor executor = executors.get(job.getType());
        if (executor != null) {
            executor.discard(job); // e.g. a paused compression job's growing output and segments
        }
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
            stopNowRequested = false;
            cancelRequested = false;
            current = next;
            next.clearError();
            update(next, JobState.RUNNING);
        }
        persist.run();
        return next;
    }

    private void runJob(JobRecord job) {
        JobExecutor.Outcome outcome;
        try {
            JobExecutor executor = executors.get(job.getType());
            if (executor == null) {
                throw new IllegalStateException("No executor for " + job.getType());
            }
            outcome = executor.execute(job, context(job));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Job failed", e);
            synchronized (project) {
                job.fail("error.io", null, String.valueOf(e.getMessage()));
            }
            outcome = JobExecutor.Outcome.FAILED;
        } catch (RuntimeException e) {
            // a broken record (e.g. hand-edited project) must not leave the job RUNNING or kill the worker
            LOG.log(Level.SEVERE, "Unexpected job failure", e);
            synchronized (project) {
                job.fail("error.internal", String.valueOf(e), e.getClass().getSimpleName());
            }
            outcome = JobExecutor.Outcome.FAILED;
        } finally {
            current = null;
            tokens.clear();
        }
        synchronized (project) {
            // Pause followed by Resume while the job was still finishing its segments: run it again right away
            boolean resumedMeanwhile = outcome == JobExecutor.Outcome.PAUSED && !pauseRequested && !stopNowRequested;
            switch (outcome) {
                case DONE -> update(job, JobState.DONE);
                case PAUSED -> update(job, resumedMeanwhile ? JobState.PENDING : JobState.PAUSED);
                case CANCELLED -> update(job, JobState.CANCELLED);
                case FAILED -> {
                    job.setState(JobState.FAILED);
                    job.setUpdated(clock.instant());
                    listener.jobChanged(job);
                }
            }
        }
        persist.run();
    }

    private JobContext context(JobRecord job) {
        return new JobContext() {
            @Override
            public boolean pauseRequested() {
                return pauseRequested;
            }

            @Override
            public boolean stopNowRequested() {
                return stopNowRequested;
            }

            @Override
            public boolean cancelRequested() {
                return cancelRequested;
            }

            @Override
            public void track(CancellationToken token) {
                tokens.add(token);
                if (stopNowRequested || cancelRequested) {
                    token.cancel();
                }
            }

            @Override
            public void untrack(CancellationToken token) {
                tokens.remove(token);
            }

            @Override
            public void progress(Progress progress) {
                listener.progress(job, progress);
            }

            @Override
            public void save() {
                persist.run();
                listener.jobChanged(job);
            }

            @Override
            public Object lock() {
                return project;
            }

            @Override
            public Clock clock() {
                return clock;
            }
        };
    }

    private void update(JobRecord job, JobState state) {
        job.setState(state);
        job.setUpdated(clock.instant());
        listener.jobChanged(job);
    }
}
