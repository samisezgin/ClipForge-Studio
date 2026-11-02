package com.clipforge.core.compress;

import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.FfmpegProcess;
import com.clipforge.core.ffmpeg.FfmpegResult;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.job.JobContext;
import com.clipforge.core.job.JobExecutor;
import com.clipforge.core.mp4.FragmentedMp4;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.util.DiskCheck;
import com.clipforge.core.util.Formats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Compresses a video in independent time segments and appends each finished segment, in order, to a growing
 * fragmented MP4 ({@code <name>.part.mp4}) that can be played while encoding. When all segments are in, the video
 * is muxed with the audio track into the final MP4 (standard layout, index at the front).
 * <p>
 * Pause lets running segments finish; stop-now discards them. Either way the job resumes from the segment table in
 * the project file, after {@link CompressRecovery#reconcile} has checked the files on disk (docs/RECOVERY.md).
 */
public final class SegmentedEncoder implements JobExecutor {

    private static final Logger LOG = Logger.getLogger(SegmentedEncoder.class.getName());
    /** Media timescale of every segment, so their fragments can be joined. */
    static final int TIMESCALE = 90_000;
    /** Target wall-clock work per segment for the automatic segment length. */
    private static final double TARGET_SEGMENT_WORK_SECONDS = 50;
    private static final long MIN_TAIL_MS = 5_000;

    private final FfmpegTools tools;

    public SegmentedEncoder(FfmpegTools tools) {
        this.tools = tools;
    }

    private record SegmentResult(SegmentRecord segment, FfmpegResult result, double wallSeconds) {
    }

    @Override
    public Outcome execute(JobRecord job, JobContext ctx) throws IOException {
        CompressState state = job.getCompress();
        CompressSpec spec = state.getSpec();
        Path out = Path.of(spec.output());
        Path part = CompressRecovery.partFile(spec);
        Path work = CompressRecovery.workDir(spec);

        if (Files.exists(out)) {
            boolean allAppended = !state.getSegments().isEmpty()
                    && state.getSegments().stream().allMatch(s -> s.getState() == SegmentState.APPENDED);
            if (allAppended) {
                // the final file was moved into place; only the clean-up or the DONE state was not saved
                CompressRecovery.cleanUp(spec, false);
                return Outcome.DONE;
            }
            throw new FileAlreadyExistsException(out.toString());
        }
        Files.createDirectories(work);
        synchronized (ctx.lock()) {
            if (state.getSegments().isEmpty()) {
                state.getSegments().addAll(plan(0, spec.totalDurationMs(), segmentMs(spec.options(), 0), 0));
            }
            CompressRecovery.reconcile(state);
        }
        ctx.save();

        Outcome space = checkSpace(job, ctx, state, out);
        if (space != null) {
            return space;
        }
        List<String> input = inputArgs(spec, work);
        if (spec.options().audio() == AudioMode.KEEP && spec.sourceHasAudio() && !state.isAudioReady()) {
            Outcome a = prepareAudio(job, ctx, input, work.resolve("audio.m4a"));
            if (a != null) {
                return a;
            }
            synchronized (ctx.lock()) {
                state.setAudioReady(true);
            }
            ctx.save();
        }

        Outcome o = encodeSegments(job, ctx, state, input, part);
        if (o != null) {
            return o;
        }
        return finish(job, ctx, state, part, work, out);
    }

    // ------------------------------------------------------------------ segments

    /** Plans segments of {@code lengthMs} covering [fromMs, toMs); a short tail is merged into the last segment. */
    static List<SegmentRecord> plan(long fromMs, long toMs, long lengthMs, int firstIndex) {
        List<SegmentRecord> list = new ArrayList<>();
        long start = fromMs;
        int index = firstIndex;
        while (start < toMs) {
            long end = Math.min(toMs, start + lengthMs);
            if (toMs - end < MIN_TAIL_MS) {
                end = toMs;
            }
            list.add(new SegmentRecord(index++, start, end));
            start = end;
        }
        return list;
    }

    private static long segmentMs(CompressOptions o, double measuredSpeed) {
        if (o.segmentSeconds() > 0) {
            return o.segmentSeconds() * 1000L;
        }
        if (measuredSpeed > 0) {
            double seconds = Math.max(20, Math.min(600, measuredSpeed * TARGET_SEGMENT_WORK_SECONDS));
            return Math.round(seconds * 1000);
        }
        return o.profile().defaultSegmentSeconds() * 1000L;
    }

    private Outcome encodeSegments(JobRecord job, JobContext ctx, CompressState state, List<String> input, Path part)
            throws IOException {
        CompressSpec spec = state.getSpec();
        int concurrency = spec.options().concurrency();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform().daemon(true)
                .name("clipforge-segment-", 0).factory());
        CompletionService<SegmentResult> done = new ExecutorCompletionService<>(pool);
        Map<SegmentRecord, Progress> live = new ConcurrentHashMap<>();
        Map<SegmentRecord, CancellationToken> running = new ConcurrentHashMap<>();
        FragmentedMp4.Info base = Files.exists(part) && Files.size(part) > 0 ? FragmentedMp4.inspect(part) : null;
        Outcome failure = null;
        try {
            while (true) {
                if (failure == null && !ctx.stopNowRequested() && !ctx.cancelRequested()) {
                    while (running.size() < concurrency && !ctx.pauseRequested()) {
                        SegmentRecord next = nextPending(ctx, state);
                        if (next == null) {
                            break;
                        }
                        CancellationToken token = new CancellationToken();
                        ctx.track(token);
                        running.put(next, token);
                        done.submit(() -> encode(spec, input, next, token, p -> {
                            live.put(next, p);
                            report(ctx, state, live);
                        }));
                    }
                }
                if (running.isEmpty()) {
                    break;
                }
                Future<SegmentResult> f = done.poll(500, TimeUnit.MILLISECONDS);
                if (f == null) {
                    continue;
                }
                SegmentResult r = get(f);
                running.remove(r.segment());
                live.remove(r.segment());
                Path temp = CompressRecovery.segmentFile(spec, r.segment());
                if (r.result().success()) {
                    synchronized (ctx.lock()) {
                        r.segment().setState(SegmentState.ENCODED);
                        maybeReplan(state, r);
                    }
                    ctx.save();
                    base = appendReady(ctx, state, part, base);
                } else {
                    Files.deleteIfExists(temp);
                    synchronized (ctx.lock()) {
                        r.segment().setState(SegmentState.PENDING);
                    }
                    ctx.save();
                    if (!r.result().cancelled() && failure == null) {
                        // a real encoder error: stop the other segments and fail the job
                        failure = fail(job, ctx, "error.ffmpeg", tail(r.result()), String.valueOf(r.result().exitCode()));
                        running.values().forEach(CancellationToken::cancel);
                    }
                }
                report(ctx, state, live);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.values().forEach(CancellationToken::cancel);
            throw new IOException("Interrupted", e);
        } finally {
            // on errors, never leave encoders running in the background
            running.values().forEach(t -> {
                t.cancel();
                ctx.untrack(t);
            });
            pool.shutdownNow();
        }
        if (failure != null) {
            return failure;
        }
        if (ctx.cancelRequested()) {
            CompressRecovery.cleanUp(spec, false);
            return Outcome.CANCELLED;
        }
        boolean complete = state.getSegments().stream().allMatch(s -> s.getState() == SegmentState.APPENDED);
        if (!complete) {
            return Outcome.PAUSED; // paused or stopped; resumes from the segment table
        }
        return null;
    }

    private static SegmentRecord nextPending(JobContext ctx, CompressState state) {
        synchronized (ctx.lock()) {
            for (SegmentRecord s : state.getSegments()) {
                if (s.getState() == SegmentState.PENDING) {
                    s.setState(SegmentState.ENCODING);
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * After the first segment of an automatic-length job, re-plan the untouched tail so each segment takes about
     * {@link #TARGET_SEGMENT_WORK_SECONDS} of work at the measured speed. Caller holds the project monitor.
     */
    private static void maybeReplan(CompressState state, SegmentResult r) {
        if (state.getMeasuredSpeed() > 0 || r.wallSeconds() <= 0) {
            return;
        }
        double speed = r.segment().durationMs() / 1000.0 / r.wallSeconds();
        state.setMeasuredSpeed(speed);
        CompressSpec spec = state.getSpec();
        if (spec.options().segmentSeconds() > 0) {
            return;
        }
        List<SegmentRecord> segs = state.getSegments();
        int firstUntouched = segs.size();
        for (int i = segs.size() - 1; i >= 0 && segs.get(i).getState() == SegmentState.PENDING; i--) {
            firstUntouched = i;
        }
        if (firstUntouched >= segs.size()) {
            return;
        }
        long from = segs.get(firstUntouched).getStartMs();
        List<SegmentRecord> tail = plan(from, spec.totalDurationMs(), segmentMs(spec.options(), speed),
                segs.get(firstUntouched).getIndex());
        segs.subList(firstUntouched, segs.size()).clear();
        segs.addAll(tail);
        LOG.info(() -> String.format(Locale.ROOT, "Measured %.1fx, re-planned %d segments", speed, tail.size()));
    }

    /** Appends every finished segment that is next in line; returns the (possibly new) base info. */
    private FragmentedMp4.Info appendReady(JobContext ctx, CompressState state, Path part, FragmentedMp4.Info base)
            throws IOException {
        CompressSpec spec = state.getSpec();
        while (true) {
            SegmentRecord next;
            long ticks;
            int seq;
            synchronized (ctx.lock()) {
                next = state.getSegments().stream().filter(s -> s.getState() != SegmentState.APPENDED)
                        .findFirst().orElse(null);
                if (next == null || next.getState() != SegmentState.ENCODED) {
                    return base;
                }
                ticks = state.getCommittedTicks();
                seq = state.getNextSequence();
            }
            Path temp = CompressRecovery.segmentFile(spec, next);
            FragmentedMp4.AppendResult r = FragmentedMp4.append(part, base, temp, ticks, seq); // flushed to disk
            if (base == null) {
                base = FragmentedMp4.inspect(temp);
            }
            synchronized (ctx.lock()) {
                next.setState(SegmentState.APPENDED);
                state.setCommittedBytes(r.fileSize());
                state.setCommittedTicks(r.endTicks());
                state.setNextSequence(r.nextSequence());
            }
            ctx.save(); // committed point persisted before the temp file disappears
            Files.deleteIfExists(temp);
        }
    }

    private SegmentResult encode(CompressSpec spec, List<String> input, SegmentRecord s, CancellationToken token,
                                 java.util.function.Consumer<Progress> progress) throws IOException {
        Path temp = CompressRecovery.segmentFile(spec, s);
        Files.deleteIfExists(temp);
        double from = s.getStartMs() / 1000.0;
        double to = s.getEndMs() / 1000.0;
        List<String> cmd = new ArrayList<>();
        cmd.add(tools.ffmpeg().toString());
        cmd.addAll(List.of("-ss", seconds(from), "-copyts", "-t", seconds(to - from + 2)));
        cmd.addAll(input);
        cmd.addAll(List.of("-map", "0:v:0", "-vf", "trim=start=" + seconds(from) + ":end=" + seconds(to),
                "-fps_mode", "passthrough"));
        cmd.addAll(spec.options().profile().videoArgs(spec.options().quality()));
        cmd.addAll(List.of("-an", "-sn", "-dn", "-video_track_timescale", String.valueOf(TIMESCALE),
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-f", "mp4", "-y", temp.toString()));
        long start = System.nanoTime();
        Duration length = Duration.ofMillis(s.durationMs());
        FfmpegResult r = FfmpegProcess.run(cmd, length, p -> {
            // with -copyts the reported time can be absolute (source time); make it segment-relative
            long ms = p.processed().toMillis();
            long rel = ms > s.durationMs() + 1000 ? ms - s.getStartMs() : ms;
            progress.accept(new Progress(Duration.ofMillis(Math.max(0, Math.min(rel, s.durationMs()))), length,
                    p.speed(), p.outputBytes()));
        }, token);
        if (r.success() && !CompressRecovery.validSegment(temp, s)) {
            r = new FfmpegResult(1, false, List.of("Encoded segment " + s.getIndex() + " is incomplete or too short"));
        }
        return new SegmentResult(s, r, (System.nanoTime() - start) / 1e9);
    }

    private static void report(JobContext ctx, CompressState state, Map<SegmentRecord, Progress> live) {
        long done;
        synchronized (ctx.lock()) {
            done = state.getSegments().stream()
                    .filter(s -> s.getState() == SegmentState.APPENDED || s.getState() == SegmentState.ENCODED)
                    .mapToLong(SegmentRecord::durationMs).sum();
        }
        double speed = 0;
        for (Progress p : live.values()) {
            done += p.processed().toMillis();
            speed += p.speed();
        }
        if (speed <= 0) {
            speed = state.getMeasuredSpeed();
        }
        ctx.progress(new Progress(Duration.ofMillis(done), Duration.ofMillis(state.getSpec().totalDurationMs()),
                speed, state.getCommittedBytes()));
    }

    // ------------------------------------------------------------------ audio and final mux

    private Outcome prepareAudio(JobRecord job, JobContext ctx, List<String> input, Path audio) throws IOException {
        for (boolean copy : new boolean[]{true, false}) {
            List<String> cmd = new ArrayList<>();
            cmd.add(tools.ffmpeg().toString());
            cmd.addAll(input);
            cmd.addAll(List.of("-map", "0:a:0", "-vn", "-sn", "-dn"));
            cmd.addAll(copy ? List.of("-c:a", "copy") : List.of("-c:a", "aac", "-b:a", "160k"));
            cmd.addAll(List.of("-f", "mp4", "-y", audio.toString()));
            CancellationToken token = new CancellationToken();
            ctx.track(token);
            try {
                FfmpegResult r = FfmpegProcess.run(cmd, Duration.ZERO, null, token);
                if (r.success()) {
                    return null;
                }
                if (r.cancelled()) {
                    return ctx.cancelRequested() ? Outcome.CANCELLED : Outcome.PAUSED;
                }
                if (!copy) {
                    return fail(job, ctx, "error.ffmpeg", tail(r), String.valueOf(r.exitCode()));
                }
                LOG.info("Audio cannot be copied into MP4, re-encoding to AAC");
            } finally {
                ctx.untrack(token);
            }
        }
        return null;
    }

    private Outcome finish(JobRecord job, JobContext ctx, CompressState state, Path part, Path work, Path out)
            throws IOException {
        CompressSpec spec = state.getSpec();
        Path audio = work.resolve("audio.m4a");
        boolean withAudio = spec.options().audio() == AudioMode.KEEP && spec.sourceHasAudio() && Files.exists(audio);
        Path muxed = work.resolve("final.mp4");
        List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg().toString(), "-i", part.toString()));
        if (withAudio) {
            cmd.addAll(List.of("-i", audio.toString()));
        }
        cmd.addAll(List.of("-map", "0:v:0"));
        if (withAudio) {
            cmd.addAll(List.of("-map", "1:a:0"));
        }
        cmd.addAll(List.of("-c", "copy", "-movflags", "+faststart", "-f", "mp4", "-y", muxed.toString()));
        CancellationToken token = new CancellationToken();
        ctx.track(token);
        FfmpegResult r;
        try {
            r = FfmpegProcess.run(cmd, Duration.ofMillis(spec.totalDurationMs()), ctx::progress, token);
        } finally {
            ctx.untrack(token);
        }
        if (r.cancelled()) {
            Files.deleteIfExists(muxed);
            return ctx.cancelRequested() ? cancelAndClean(spec) : Outcome.PAUSED;
        }
        if (!r.success()) {
            Files.deleteIfExists(muxed);
            return fail(job, ctx, "error.ffmpeg", tail(r), String.valueOf(r.exitCode()));
        }
        Files.move(muxed, out); // never replaces an existing file
        CompressRecovery.cleanUp(spec, false);
        return Outcome.DONE;
    }

    private static Outcome cancelAndClean(CompressSpec spec) throws IOException {
        CompressRecovery.cleanUp(spec, false);
        return Outcome.CANCELLED;
    }

    // ------------------------------------------------------------------ helpers

    private Outcome checkSpace(JobRecord job, JobContext ctx, CompressState state, Path out) throws IOException {
        CompressSpec spec = state.getSpec();
        long appendedMs = state.appendedMs();
        // estimate the output from what is already encoded, otherwise assume half the input size
        double ratio = appendedMs > 0 && state.getCommittedBytes() > 0
                ? (double) state.getCommittedBytes() / appendedMs * spec.totalDurationMs() / Math.max(1, spec.totalBytes())
                : 0.5;
        long estimate = (long) (spec.totalBytes() * Math.min(1.0, ratio));
        // the growing output and the final file exist side by side for a moment
        long required = Math.max(0, 2 * estimate - state.getCommittedBytes());
        DiskCheck.Result disk = DiskCheck.check(out.toAbsolutePath().getParent(), required, estimate);
        if (!disk.enoughSpace()) {
            return fail(job, ctx, "error.diskSpace", null, Formats.size(disk.requiredBytes()),
                    Formats.size(disk.usableBytes()));
        }
        if (disk.exceedsFileSizeLimit()) {
            return fail(job, ctx, "error.fat32", null, disk.fileSystem());
        }
        return null;
    }

    /** FFmpeg input arguments: the file itself, or a concat list of the clips. */
    static List<String> inputArgs(CompressSpec spec, Path work) throws IOException {
        if (spec.inputs().size() == 1) {
            return List.of("-i", spec.inputs().getFirst());
        }
        Path list = work.resolve("inputs.txt");
        StringBuilder sb = new StringBuilder("ffconcat version 1.0\n");
        for (String in : spec.inputs()) {
            sb.append("file '").append(in.replace('\\', '/').replace("'", "'\\''")).append("'\n");
        }
        Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
        return List.of("-f", "concat", "-safe", "0", "-i", list.toString());
    }

    private static String seconds(double s) {
        return String.format(Locale.ROOT, "%.3f", s);
    }

    private static SegmentResult get(Future<SegmentResult> f) throws IOException, InterruptedException {
        try {
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        }
    }

    private static Outcome fail(JobRecord job, JobContext ctx, String key, String detail, String... args) {
        synchronized (ctx.lock()) {
            job.fail(key, detail, args);
        }
        return Outcome.FAILED;
    }

    private static String tail(FfmpegResult r) {
        List<String> lines = r.stderrText().lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 8), lines.size()));
    }
}
