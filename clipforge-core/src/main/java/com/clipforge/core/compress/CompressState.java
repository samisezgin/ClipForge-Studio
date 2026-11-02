package com.clipforge.core.compress;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent progress of a compression job: the segment table and the committed point of the growing output.
 * Mutable; all changes happen while holding the project monitor and are followed by a save.
 */
public final class CompressState {

    private CompressSpec spec;
    private List<SegmentRecord> segments = new ArrayList<>();
    /** Size of the growing {@code .part.mp4} after the last successful append. */
    private long committedBytes;
    /** Decode time (90 kHz ticks) at the end of the last appended segment. */
    private long committedTicks;
    /** {@code mfhd} sequence number for the next appended fragment. */
    private int nextSequence = 1;
    private boolean audioReady;
    /** Measured encoding speed (media seconds per wall-clock second), 0 until the first segment finished. */
    private double measuredSpeed;

    public CompressState() {
        // for Jackson
    }

    public CompressState(CompressSpec spec) {
        this.spec = spec;
    }

    public CompressSpec getSpec() {
        return spec;
    }

    public void setSpec(CompressSpec spec) {
        this.spec = spec;
    }

    public List<SegmentRecord> getSegments() {
        return segments;
    }

    public void setSegments(List<SegmentRecord> segments) {
        this.segments = segments == null ? new ArrayList<>() : new ArrayList<>(segments);
    }

    public long getCommittedBytes() {
        return committedBytes;
    }

    public void setCommittedBytes(long committedBytes) {
        this.committedBytes = committedBytes;
    }

    public long getCommittedTicks() {
        return committedTicks;
    }

    public void setCommittedTicks(long committedTicks) {
        this.committedTicks = committedTicks;
    }

    public int getNextSequence() {
        return nextSequence;
    }

    public void setNextSequence(int nextSequence) {
        this.nextSequence = nextSequence;
    }

    public boolean isAudioReady() {
        return audioReady;
    }

    public void setAudioReady(boolean audioReady) {
        this.audioReady = audioReady;
    }

    public double getMeasuredSpeed() {
        return measuredSpeed;
    }

    public void setMeasuredSpeed(double measuredSpeed) {
        this.measuredSpeed = measuredSpeed;
    }

    /** Media time already in the growing output, in milliseconds. */
    public long appendedMs() {
        return segments.stream().filter(s -> s.getState() == SegmentState.APPENDED)
                .mapToLong(SegmentRecord::durationMs).sum();
    }
}
