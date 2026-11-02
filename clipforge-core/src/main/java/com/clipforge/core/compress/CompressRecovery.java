package com.clipforge.core.compress;

import com.clipforge.core.mp4.FragmentedMp4;
import com.clipforge.core.mp4.Mp4Reader;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.util.OutputNaming;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
                    if (!validSegment(temp, s, spec.options().speed())) {
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
        FragmentedMp4.Info info;
        try {
            info = FragmentedMp4.inspect(part);
        } catch (IOException e) {
            LOG.warning("Growing output unreadable (" + e.getMessage() + "), starting it over: " + part);
            Files.deleteIfExists(part);
            resetAppended(state);
            return;
        }
        if (size >= committed && FragmentedMp4.lastCompleteFragmentEnd(info) == committed
                && FragmentedMp4.endTicks(info) == state.getCommittedTicks()) {
            return; // consistent
        }
        keepIntactPrefix(state, part, info);
    }

    /**
     * Scenario 3: the output was shortened or damaged. Keep the appended segments that are still completely in the
     * file (their end matches a fragment end, found via the fragments' tfdt), cut the file after the last of them,
     * and re-encode the rest.
     */
    private static void keepIntactPrefix(CompressState state, Path part, FragmentedMp4.Info info) throws IOException {
        long tolerance = SegmentedEncoder.TIMESCALE / 5; // 200 ms: a segment ends within a frame of its plan
        List<FragmentedMp4.Fragment> fragments = info.fragments();
        long keepBytes = 0;
        long keepTicks = 0;
        int keepSeq = 1;
        int j = 0;
        boolean intact = true;
        for (SegmentRecord s : state.getSegments()) {
            if (s.getState() != SegmentState.APPENDED) {
                continue;
            }
            long segEnd = Math.round(s.getEndMs() / state.getSpec().options().speed()
                    * (SegmentedEncoder.TIMESCALE / 1000.0));
            boolean found = false;
            while (intact && j < fragments.size()) {
                FragmentedMp4.Fragment f = fragments.get(j++);
                long fEnd = f.decodeTime() + f.durationTicks();
                if (Math.abs(fEnd - segEnd) <= tolerance) {
                    keepBytes = f.mdat().end();
                    keepTicks = fEnd;
                    keepSeq = j + 1;
                    found = true;
                    break;
                }
                if (fEnd > segEnd + tolerance) {
                    break;
                }
            }
            if (!found) {
                intact = false;
                s.setState(SegmentState.PENDING);
            }
        }
        if (keepBytes == 0) {
            LOG.warning("No intact segment left in " + part + ", starting the output over");
            Files.deleteIfExists(part);
            resetAppended(state);
            return;
        }
        FragmentedMp4.truncate(part, keepBytes);
        state.setCommittedBytes(keepBytes);
        state.setCommittedTicks(keepTicks);
        state.setNextSequence(keepSeq);
        LOG.warning("Growing output was damaged; kept " + state.appendedMs() / 1000 + " s, re-encoding the rest");
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

    /** @param speed speed-up factor: the encoded segment is that many times shorter than its source range */
    static boolean validSegment(Path temp, SegmentRecord s, double speed) {
        try {
            if (!Files.isRegularFile(temp)) {
                return false;
            }
            FragmentedMp4.Info info = FragmentedMp4.inspect(temp);
            long ms = info.durationTicks() * 1000 / Math.max(1, info.timescale());
            // the file must end on a box boundary (FFmpeg may add an mfra index after the last fragment)
            return info.samples() > 0 && !Mp4Reader.scan(temp).truncated()
                    && Math.abs(ms - Math.round(s.durationMs() / speed)) <= DURATION_TOLERANCE_MS;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Starts a job over (e.g. after its source changed): forgets all segments and deletes the job's partial files.
     * Caller holds the project monitor.
     */
    public static void restart(CompressState state) throws IOException {
        cleanUp(state.getSpec(), false);
        state.getSegments().clear();
        resetAppended(state);
        state.setAudioReady(false);
        state.setMeasuredSpeed(0);
    }

    /**
     * Like {@link #restart(CompressState)}, but if sources were already deleted during the work the growing output
     * is their only copy: it is kept next to the output instead of being deleted.
     */
    public static void restart(JobRecord job) throws IOException {
        preservePartial(job);
        restart(job.getCompress());
    }

    /**
     * Deletes the job's partial files of any job state: used when a compression job is cancelled or removed. If
     * sources were deleted during the work, the growing output is kept (see {@link #preservePartial}).
     */
    public static void discard(JobRecord job) {
        if (job.getCompress() == null || job.getCompress().getSpec() == null) {
            return;
        }
        try {
            preservePartial(job);
            cleanUp(job.getCompress().getSpec(), false);
        } catch (IOException e) {
            LOG.warning("Cannot remove partial files of " + job.output() + ": " + e.getMessage());
        }
    }

    /**
     * Sources deleted during the work exist only in the growing output. Before it would be thrown away (cancel,
     * remove, start over), it is renamed to {@code <name>_incomplete.mp4} next to the output and stays playable.
     *
     * @return the kept file, or {@code null} if nothing needed keeping
     */
    public static Path preservePartial(JobRecord job) throws IOException {
        if (job.getDeletedSources().isEmpty() || job.getCompress() == null || job.getCompress().getSpec() == null) {
            return null;
        }
        Path part = partFile(job.getCompress().getSpec());
        if (!Files.isRegularFile(part) || Files.size(part) == 0) {
            return null;
        }
        Path out = Path.of(job.getCompress().getSpec().output());
        String name = out.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        Path kept = OutputNaming.unique(out.getParent(), stem + "_incomplete.mp4", List.of());
        Files.move(part, kept); // never replaces an existing file
        job.setKeptPartial(kept.toString());
        LOG.warning("Sources were deleted during the work; kept the partial output as " + kept);
        return kept;
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
