package com.clipforge.core.project;

public enum JobState {
    PENDING,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED;

    /** A job in this state still has work to do and is offered for resumption. */
    public boolean unfinished() {
        return this == PENDING || this == RUNNING || this == PAUSED;
    }

    public boolean terminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
