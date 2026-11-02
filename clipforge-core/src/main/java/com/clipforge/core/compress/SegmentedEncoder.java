package com.clipforge.core.compress;

import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicRenderer;
import com.clipforge.core.audio.MusicSettings;
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
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Compresses a video in independent time segments and appends each finished segment, in order, to a growing
 * fragmented MP4 ({@code <name>.part.mp4}) that can be played while encoding. When all segments are in, the video
 * is muxed with the audio track into the final MP4 (standard layout, index at the front).
 * <p>
 * Segments never cross source boundaries. Each is cut from one file with accurate input seeking and a {@code trim}
 * on the source's own timestamps ({@code -copyts}, offset by the stream's {@code start_time}), which selects every
 * frame exactly once. On append, a segment is anchored at its planned position on the output timeline, so frame
 * timing errors cannot accumulate over hours of footage.
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
    /** Rough output/input size ratio before anything has been encoded (for the free-space check). */
    private static final double INITIAL_SIZE_RATIO = 0.4;

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
        if (spec.sources().isEmpty()) {
            return fail(job, ctx, "error.internal", "job has no sources", "CompressSpec");
        }
        Path out = Path.of(spec.output());
        Path part = CompressRecovery.partFile(spec);
        Path work = CompressRecovery.workDir(spec);
        Path audio = work.resolve("audio.m4a");

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
                state.getSegments().addAll(plan(spec, segmentMs(spec.options(), 0), 0, 0, 0));
            }
            CompressRecovery.reconcile(state);
            if (state.isAudioReady() && !Files.isRegularFile(audio)) {
                state.setAudioReady(false); // the work folder was cleaned up meanwhile: extract again
            }
        }
        ctx.save();

        Outcome space = checkSpace(job, ctx, state, out, false);
        if (space != null) {
            return space;
        }
        if (keepsAudio(spec) && !state.isAudioReady()) {
            Outcome a = prepareAudio(job, ctx, spec, work, audio);
            if (a != null) {
                return a;
            }
            synchronized (ctx.lock()) {
                state.setAudioReady(true);
            }
            ctx.save();
        }

        Outcome o = encodeSegments(job, ctx, state, part);
        if (o != null) {
            return o;
        }
        Outcome finalSpace = checkSpace(job, ctx, state, out, true);
        if (finalSpace != null) {
            return finalSpace;
        }
        return finish(job, ctx, state, part, work, out);
    }

    @Override
    public void discard(JobRecord job) {
        CompressRecovery.discard(job);
    }

    /** Whether the output gets an audio track: the original one, or background music. */
    private static boolean keepsAudio(CompressSpec spec) {
        return switch (spec.options().audio()) {
            case KEEP -> spec.sourceHasAudio();
            case MUSIC -> true;
            case REMOVE -> false;
        };
    }

    // ------------------------------------------------------------------ planning

    /**
     * Plans segments of about {@code lengthMs} from source {@code fromInput} at {@code fromLocalMs} to the end of the
     * last source. Segments never cross sources; a short tail is merged into the previous segment of the same source.
     */
    static List<SegmentRecord> plan(CompressSpec spec, long lengthMs, int fromInput, long fromLocalMs, int firstIndex) {
        List<SegmentRecord> list = new ArrayList<>();
        int index = firstIndex;
        for (int i = fromInput; i < spec.sources().size(); i++) {
            long duration = spec.sources().get(i).durationMs();
            long offset = spec.offsetMs(i);
            long local = i == fromInput ? fromLocalMs : 0;
            while (local < duration) {
                long end = Math.min(duration, local + lengthMs);
                if (duration - end < MIN_TAIL_MS) {
                    end = duration;
                }
                list.add(new SegmentRecord(index++, i, local, offset + local, offset + end));
                local = end;
            }
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

    /**
     * After the first segment of an automatic-length job, re-plan the untouched tail so each segment takes about
     * {@link #TARGET_SEGMENT_WORK_SECONDS} of work at the measured speed. Caller holds the project monitor.
     */
    private static void maybeReplan(CompressState state, SegmentResult r, int concurrency) {
        if (state.getMeasuredSpeed() > 0 || r.wallSeconds() <= 0) {
            return;
        }
        // with parallel segments one encoder only gets a share of the hardware; this is a per-encoder speed
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
        SegmentRecord first = segs.get(firstUntouched);
        List<SegmentRecord> tail = plan(spec, segmentMs(spec.options(), speed), first.getInput(),
                first.getLocalStartMs(), first.getIndex());
        segs.subList(firstUntouched, segs.size()).clear();
        segs.addAll(tail);
        LOG.info(() -> String.format(Locale.ROOT, "Measured %.1fx per encoder (%d parallel), re-planned %d segments",
                speed, concurrency, tail.size()));
    }

    // ------------------------------------------------------------------ encoding

    private Outcome encodeSegments(JobRecord job, JobContext ctx, CompressState state, Path part)
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
                        done.submit(() -> encode(spec, next, token, p -> {
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
                ctx.untrack(running.remove(r.segment()));
                Path temp = CompressRecovery.segmentFile(spec, r.segment());
                if (r.result().success()) {
                    synchronized (ctx.lock()) {
                        r.segment().setState(SegmentState.ENCODED);
                        maybeReplan(state, r, concurrency);
                    }
                    live.remove(r.segment());
                    ctx.save();
                    base = appendReady(ctx, state, part, base);
                } else {
                    live.remove(r.segment());
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
            return cancelAndClean(spec);
        }
        boolean complete = state.getSegments().stream().allMatch(s -> s.getState() == SegmentState.APPENDED);
        return complete ? null : Outcome.PAUSED; // paused or stopped; resumes from the segment table
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
                // anchor at the planned position (no cumulative drift), never before the previous segment's end
                ticks = Math.max(state.getCommittedTicks(), Math.round(next.getStartMs() * (TIMESCALE / 1000.0)));
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

    private SegmentResult encode(CompressSpec spec, SegmentRecord s, CancellationToken token,
                                 Consumer<Progress> progress) throws IOException {
        Path temp = CompressRecovery.segmentFile(spec, s);
        Files.deleteIfExists(temp);
        CompressInput in = spec.sources().get(s.getInput());
        double start = in.videoStartUs() / 1e6;
        double from = s.getLocalStartMs() / 1000.0;
        double length = s.durationMs() / 1000.0;
        boolean lastOfSource = s.getLocalStartMs() + s.durationMs() >= in.durationMs();
        List<String> cmd = new ArrayList<>();
        cmd.add(tools.ffmpeg().toString());
        // -ss is relative to the file start; with -copyts the frames keep their own timestamps, so trim must add
        // the stream's start_time. The last segment of a source has no end, so no trailing frame is ever lost.
        cmd.addAll(List.of("-ss", seconds(from), "-copyts"));
        if (!lastOfSource) {
            cmd.addAll(List.of("-t", seconds(length + 2)));
        }
        String vf = "trim=start=" + seconds(start + from) + (lastOfSource ? "" : ":end=" + seconds(start + from + length));
        if (spec.options().overlayTime() && in.startEpochMs() > 0) {
            var font = TimeOverlay.font();
            if (font.isPresent()) {
                vf += "," + TimeOverlay.filter(font.get(), in.startEpochMs(), in.videoStartUs());
            } else {
                LOG.warning("No font found for the date/time overlay; encoding without it");
            }
        }
        // the filter graph goes through a file: its quoting does not survive every command-line parser
        Path vfFile = temp.resolveSibling(s.fileName() + ".vf.txt");
        Files.writeString(vfFile, vf, StandardCharsets.UTF_8);
        cmd.addAll(List.of("-i", in.path(), "-map", "0:v:0", "-/vf", vfFile.toString(), "-fps_mode", "passthrough"));
        cmd.addAll(spec.options().profile().videoArgs(spec.options().quality()));
        cmd.addAll(List.of("-an", "-sn", "-dn", "-video_track_timescale", String.valueOf(TIMESCALE),
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-f", "mp4", "-y", temp.toString()));
        long t0 = System.nanoTime();
        Duration total = Duration.ofMillis(s.durationMs());
        double fps = in.frameRate() > 0 ? in.frameRate() : 30;
        FfmpegResult r = FfmpegProcess.run(cmd, total, p -> {
            // out_time is not usable with -copyts; frames are
            long ms = Math.min(s.durationMs(), Math.round(p.frames() / fps * 1000));
            progress.accept(new Progress(Duration.ofMillis(ms), total, p.speed(), p.outputBytes(), p.frames()));
        }, token);
        Files.deleteIfExists(vfFile);
        if (r.success() && !CompressRecovery.validSegment(temp, s)) {
            r = new FfmpegResult(1, false, List.of("Encoded segment " + s.getIndex() + " is incomplete or too short"));
        }
        return new SegmentResult(s, r, (System.nanoTime() - t0) / 1e9);
    }

    private static void report(JobContext ctx, CompressState state, Map<SegmentRecord, Progress> live) {
        long done;
        long committed;
        synchronized (ctx.lock()) {
            done = state.getSegments().stream()
                    .filter(s -> s.getState() == SegmentState.APPENDED || s.getState() == SegmentState.ENCODED)
                    .mapToLong(SegmentRecord::durationMs).sum();
            committed = state.getCommittedBytes();
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
                speed, committed));
    }

    // ------------------------------------------------------------------ audio and final mux

    private Outcome prepareAudio(JobRecord job, JobContext ctx, CompressSpec spec, Path work, Path audio)
            throws IOException {
        if (spec.options().audio() == AudioMode.MUSIC) {
            return prepareMusic(job, ctx, spec, work, audio);
        }
        return extractOriginal(job, ctx, spec, work, audio);
    }

    private Outcome prepareMusic(JobRecord job, JobContext ctx, CompressSpec spec, Path work, Path audio)
            throws IOException {
        MusicSettings s = spec.options().music();
        Path original = null;
        if (s.mixOriginal() && spec.sourceHasAudio()) {
            original = work.resolve("original.m4a");
            Outcome o = extractOriginal(job, ctx, spec, work, original);
            if (o != null) {
                return o;
            }
        }
        CancellationToken token = new CancellationToken();
        ctx.track(token);
        try {
            FfmpegResult r = new MusicRenderer(tools).render(s, spec.totalDurationMs() / 1000.0, original, work,
                    audio, token);
            if (r.cancelled()) {
                Files.deleteIfExists(audio);
                return ctx.cancelRequested() ? cancelAndClean(spec) : Outcome.PAUSED;
            }
            if (!r.success()) {
                Files.deleteIfExists(audio);
                return fail(job, ctx, "error.ffmpeg", tail(r), String.valueOf(r.exitCode()));
            }
            return null;
        } catch (MusicRenderer.TrackException e) {
            return fail(job, ctx, "error.musicTrack", null, e.track());
        } finally {
            ctx.untrack(token);
            if (original != null) {
                Files.deleteIfExists(original);
            }
        }
    }

    private Outcome extractOriginal(JobRecord job, JobContext ctx, CompressSpec spec, Path work, Path audio)
            throws IOException {
        List<String> input = audioInputArgs(spec, work);
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
                if (r.cancelled()) {
                    Files.deleteIfExists(audio);
                    return ctx.cancelRequested() ? cancelAndClean(spec) : Outcome.PAUSED;
                }
                if (r.success()) {
                    return null;
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
        boolean withAudio = keepsAudio(spec);
        if (withAudio && !Files.isRegularFile(audio)) {
            // deleted meanwhile (e.g. by a clean-up tool): prepare it again
            synchronized (ctx.lock()) {
                state.setAudioReady(false);
            }
            Outcome a = prepareAudio(job, ctx, spec, work, audio);
            if (a != null) {
                return a;
            }
            synchronized (ctx.lock()) {
                state.setAudioReady(true);
            }
            ctx.save();
        }
        Path muxed = work.resolve("final.mp4");
        // keep the source's offset between video and audio start (both tracks start at 0 in our files);
        // background music simply starts with the video
        CompressInput first = spec.sources().getFirst();
        double avOffset = spec.options().audio() == AudioMode.KEEP
                ? (first.videoStartUs() - first.audioStartUs()) / 1e6 : 0;
        List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg().toString()));
        if (withAudio && avOffset > 0.001) {
            cmd.addAll(List.of("-itsoffset", seconds(avOffset)));
        }
        cmd.addAll(List.of("-i", part.toString()));
        if (withAudio) {
            if (avOffset < -0.001) {
                cmd.addAll(List.of("-itsoffset", seconds(-avOffset)));
            }
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
            ctx.progress(new Progress(Duration.ofMillis(spec.totalDurationMs()),
                    Duration.ofMillis(spec.totalDurationMs()), 0, state.getCommittedBytes(), -1));
            r = FfmpegProcess.run(cmd, Duration.ZERO, null, token);
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

    /**
     * Free space: at the start, room for the growing output (estimated from what is encoded so far); before the
     * final mux, room for a full copy of the growing output plus audio.
     */
    private Outcome checkSpace(JobRecord job, JobContext ctx, CompressState state, Path out, boolean finalMux)
            throws IOException {
        CompressSpec spec = state.getSpec();
        long required;
        long largest;
        if (finalMux) {
            Path part = CompressRecovery.partFile(spec);
            Path audio = CompressRecovery.workDir(spec).resolve("audio.m4a");
            long partSize = Files.exists(part) ? Files.size(part) : 0;
            long audioSize = Files.exists(audio) ? Files.size(audio) : 0;
            required = partSize + audioSize;
            largest = required;
        } else {
            long appendedMs = state.appendedMs();
            double ratio = appendedMs > 0 && state.getCommittedBytes() > 0
                    ? (double) state.getCommittedBytes() / appendedMs * spec.totalDurationMs() / Math.max(1, spec.totalBytes())
                    : INITIAL_SIZE_RATIO;
            long estimate = (long) (spec.totalBytes() * Math.min(1.0, ratio));
            required = Math.max(0, estimate - state.getCommittedBytes());
            largest = estimate;
        }
        DiskCheck.Result disk = DiskCheck.check(out.toAbsolutePath().getParent(), required, largest);
        if (!disk.enoughSpace()) {
            return fail(job, ctx, "error.diskSpace", null, Formats.size(disk.requiredBytes()),
                    Formats.size(disk.usableBytes()));
        }
        if (disk.exceedsFileSizeLimit()) {
            return fail(job, ctx, "error.fat32", null, disk.fileSystem());
        }
        return null;
    }

    /** Audio input: the file itself, or a concat list of the sources (timestamps follow the source durations). */
    static List<String> audioInputArgs(CompressSpec spec, Path work) throws IOException {
        if (spec.sources().size() == 1) {
            return List.of("-i", spec.sources().getFirst().path());
        }
        Path list = work.resolve("inputs.txt");
        StringBuilder sb = new StringBuilder("ffconcat version 1.0\n");
        for (String in : spec.inputs()) {
            sb.append("file '").append(in.replace('\\', '/').replace("'", "'\\''")).append("'\n");
        }
        Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
        return List.of("-f", "concat", "-safe", "0", "-i", list.toString());
    }

    static String seconds(double s) {
        return String.format(Locale.ROOT, "%.6f", s);
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
