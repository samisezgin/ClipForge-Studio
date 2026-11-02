package com.clipforge.core.compress;

import com.clipforge.core.mp4.FragmentedMp4;
import com.clipforge.core.util.OutputNaming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Brings a compression job's on-disk files back in line with its persisted state before (re)starting it.
 * Implements the scenarios of docs/RECOVERY.md; safe to run on every start, after a pause as well as after a crash.
 */
public final class CompressRecovery {

    private static final Logger LOG = Logger.getLogger(CompressRecovery.class.getName());
    /** Allowed difference between a segment's planned and encoded duration. */
    private static final long DURATION_TOLERANCE_MS = 1500;

    private CompressRecovery() {
    }

    public static Path partFile(CompressSpec spec) {
        return OutputNaming.partOf(Path.of(spec.output()));
    }

    public static Path workDir(CompressSpec spec) {
        Path out = Path.of(spec.output());
        return out.resolveSibling(out.getFileName() + ".work");
    }

    public static Path segmentFile(CompressSpec spec, SegmentRecord s) {
        return workDir(spec).resolve(s.fileName());
    }

    /** Caller holds the project monitor. */
    public static void reconcile(CompressState state) throws IOException {
        CompressSpec spec = state.getSpec();
        Path part = partFile(spec);
        reconcileOutput(state, part);
        for (SegmentRecord s : state.getSegments()) {
            Path temp = segmentFile(spec, s);
            switch (s.getState()) {
                case ENCODING -> {
                    // scenario 5: interrupted while encoding
                    Files.deleteIfExists(temp);
                    s.setState(SegmentState.PENDING);
                }
                case ENCODED -> {
                    // scenario 4: keep a finished segment only if it is complete
                    if (!validSegment(temp, s)) {
                        Files.deleteIfExists(temp);
                        s.setState(SegmentState.PENDING);
                    }
                }
                case PENDING -> Files.deleteIfExists(temp);
                case APPENDED -> {
                    // its data lives in the part file; the temp file may survive a crash before deletion
                    Files.deleteIfExists(temp);
                }
            }
        }
    }

    private static void reconcileOutput(CompressState state, Path part) throws IOException {
        long committed = state.getCommittedBytes();
        if (!Files.exists(part)) {
            if (committed > 0 || state.getSegments().stream().anyMatch(s -> s.getState() == SegmentState.APPENDED)) {
                LOG.warning("Growing output missing; re-encoding appended segments: " + part);
                resetAppended(state);
            }
            return;
        }
        long size = Files.size(part);
        if (committed == 0) {
            Files.delete(part); // crashed during the very first append
            resetAppended(state);
            return;
        }
        if (size > committed) {
            // scenario 2: crashed while appending - cut back to the committed point
            FragmentedMp4.truncate(part, committed);
        }
        try {
            FragmentedMp4.Info info = FragmentedMp4.inspect(part);
            if (size < committed || FragmentedMp4.lastCompleteFragmentEnd(info) != committed
                    || FragmentedMp4.endTicks(info) != state.getCommittedTicks()) {
                throw new IOException("Output does not match the committed point");
            }
        } catch (IOException e) {
            // scenario 3: damaged or shortened output - start the output over (segments are re-encoded)
            LOG.warning("Growing output inconsistent (" + e.getMessage() + "), starting it over: " + part);
            Files.deleteIfExists(part);
            resetAppended(state);
        }
    }

    private static void resetAppended(CompressState state) {
        for (SegmentRecord s : state.getSegments()) {
            if (s.getState() == SegmentState.APPENDED) {
                s.setState(SegmentState.PENDING);
            }
        }
        state.setCommittedBytes(0);
        state.setCommittedTicks(0);
        state.setNextSequence(1);
    }

    static boolean validSegment(Path temp, SegmentRecord s) {
        try {
            if (!Files.isRegularFile(temp)) {
                return false;
            }
            FragmentedMp4.Info info = FragmentedMp4.inspect(temp);
            long ms = info.durationTicks() * 1000 / Math.max(1, info.timescale());
            // the file must end on a box boundary (FFmpeg may add an mfra index after the last fragment)
            return info.samples() > 0 && !com.clipforge.core.mp4.Mp4Reader.scan(temp).truncated()
                    && Math.abs(ms - s.durationMs()) <= DURATION_TOLERANCE_MS;
        } catch (IOException e) {
            return false;
        }
    }

    /** Deletes the work folder and the growing output (job cancelled or finished). */
    public static void cleanUp(CompressSpec spec, boolean keepPart) throws IOException {
        Path work = workDir(spec);
        if (Files.isDirectory(work)) {
            try (var files = Files.list(work)) {
                for (Path f : files.toList()) {
                    Files.deleteIfExists(f);
                }
            }
            Files.deleteIfExists(work);
        }
        if (!keepPart) {
            Files.deleteIfExists(partFile(spec));
        }
    }
}
