package com.clipforge.core.compress;

/**
 * One time slice of one source, persisted in the project file. Segments never cross source boundaries, so each is
 * cut from a single file with accurate seeking. Mutable; guarded by the project monitor.
 */
public final class SegmentRecord {

    private int index;
    /** Index of the source in {@link CompressSpec#sources()}. */
    private int input;
    /** Start within the source, relative to its video start, in milliseconds. */
    private long localStartMs;
    /** Start and end on the output timeline, in milliseconds. */
    private long startMs;
    private long endMs;
    private SegmentState state = SegmentState.PENDING;

    public SegmentRecord() {
        // for Jackson
    }

    public SegmentRecord(int index, int input, long localStartMs, long startMs, long endMs) {
        this.index = index;
        this.input = input;
        this.localStartMs = localStartMs;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getInput() {
        return input;
    }

    public void setInput(int input) {
        this.input = input;
    }

    public long getLocalStartMs() {
        return localStartMs;
    }

    public void setLocalStartMs(long localStartMs) {
        this.localStartMs = localStartMs;
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
