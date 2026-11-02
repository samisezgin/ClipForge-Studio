package com.clipforge.core.compress;

/** One time slice of the input, persisted in the project file. Mutable; guarded by the project monitor. */
public final class SegmentRecord {

    private int index;
    private long startMs;
    private long endMs;
    private SegmentState state = SegmentState.PENDING;

    public SegmentRecord() {
        // for Jackson
    }

    public SegmentRecord(int index, long startMs, long endMs) {
        this.index = index;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public long getStartMs() {
        return startMs;
    }

    public void setStartMs(long startMs) {
        this.startMs = startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public void setEndMs(long endMs) {
        this.endMs = endMs;
    }

    public SegmentState getState() {
        return state;
    }

    public void setState(SegmentState state) {
        this.state = state;
    }

    public long durationMs() {
        return endMs - startMs;
    }

    /** Temporary file name inside the job's work folder. */
    public String fileName() {
        return String.format("seg_%05d.mp4", index);
    }
}
