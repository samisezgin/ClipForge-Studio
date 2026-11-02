package com.clipforge.core.project;

public enum JobState {
    PENDING,
    RUNNING,
    /** Also used for states written by a newer version: such a job waits for the user. */
    @com.fasterxml.jackson.annotation.JsonEnumDefaultValue
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
