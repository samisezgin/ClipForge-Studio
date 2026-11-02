package com.clipforge.core.job;

import com.clipforge.core.project.JobRecord;

import java.io.IOException;

/** Performs one kind of job. Implementations must honour pause / stop-now / cancel from the context. */
public interface JobExecutor {

    enum Outcome {
        DONE,
        /** Stopped by pause or stop-now; resumable. */
        PAUSED,
        CANCELLED,
        /** {@link JobRecord#fail} has been called with the reason. */
        FAILED
    }

    Outcome execute(JobRecord job, JobContext ctx) throws IOException;
}
