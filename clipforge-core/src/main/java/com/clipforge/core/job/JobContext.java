package com.clipforge.core.job;

import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.Progress;

import java.time.Clock;

/** What a {@link JobExecutor} may observe and do while running a job. */
public interface JobContext {

    /** Lossless pause: finish work in progress, start nothing new. */
    boolean pauseRequested();

    /** Stop immediately; work in progress is discarded and the job is resumable. */
    boolean stopNowRequested();

    /** The user cancelled this job. */
    boolean cancelRequested();

    /** Registers an FFmpeg run so "stop now" and "cancel" can interrupt it. */
    void track(CancellationToken token);

    void untrack(CancellationToken token);

    void progress(Progress progress);

    /** Saves the project; call after every state change of the job record. */
    void save();

    /** Monitor guarding all job records (the project). */
    Object lock();

    Clock clock();
}
