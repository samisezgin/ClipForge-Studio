package com.clipforge.core.project;

/**
 * Deleting a job's source files, chosen by the user when starting the job.
 *
 * @param when      during the work (each source once its part of the output is safely written; only for jobs that
 *                  re-encode) or after the finished output has been checked
 * @param permanent {@code true} deletes for good; {@code false} moves to the Recycle Bin (default)
 */
public record SourceDeletion(When when, boolean permanent) {

    public enum When {
        /** Each source as soon as the output no longer needs it. Saves disk space; a damaged output cannot be redone. */
        DURING,
        /** After the job finished and its output was checked. */
        AFTER
    }

    public SourceDeletion {
        when = when == null ? When.AFTER : when;
    }
}
