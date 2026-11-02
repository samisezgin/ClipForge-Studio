package com.clipforge.core.job;

import com.clipforge.core.audio.MusicRenderer;
import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.FfmpegResult;
import com.clipforge.core.merge.MergeService;
import com.clipforge.core.merge.MergeSpec;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.util.DiskCheck;
import com.clipforge.core.util.Formats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Runs a lossless merge job. A merge is short, so pause lets it finish; stop-now discards and redoes it. */
public final class MergeExecutor implements JobExecutor {

    private final MergeService merge;

    public MergeExecutor(MergeService merge) {
        this.merge = merge;
    }

    @Override
    public Outcome execute(JobRecord job, JobContext ctx) throws IOException {
        MergeSpec spec = job.getMerge();
        Path out = Path.of(spec.output());
        Path dir = out.toAbsolutePath().getParent();
        DiskCheck.Result disk = DiskCheck.check(dir, spec.totalBytes(), spec.totalBytes());
        if (!disk.enoughSpace()) {
            return fail(job, ctx, "error.diskSpace", null,
                    Formats.size(disk.requiredBytes()), Formats.size(disk.usableBytes()));
        }
        if (disk.exceedsFileSizeLimit()) {
            return fail(job, ctx, "error.fat32", null, disk.fileSystem());
        }
        Files.createDirectories(dir);
        CancellationToken token = new CancellationToken();
        ctx.track(token);
        try {
            FfmpegResult r;
            try {
                r = merge.merge(spec, ctx::progress, token);
            } catch (MusicRenderer.TrackException e) {
                return fail(job, ctx, "error.musicTrack", null, e.track());
            }
            if (r.success() && Files.isRegularFile(out)) {
                return Outcome.DONE;
            }
            if (r.cancelled()) {
                return ctx.stopNowRequested() && !ctx.cancelRequested() ? Outcome.PAUSED : Outcome.CANCELLED;
            }
            return fail(job, ctx, "error.ffmpeg", tail(r.stderrText().lines().toList()), String.valueOf(r.exitCode()));
        } finally {
            ctx.untrack(token);
        }
    }

    @Override
    public void discard(JobRecord job) {
        if (job.getMerge() != null) {
            MergeService.discard(job.getMerge());
        }
    }

    static Outcome fail(JobRecord job, JobContext ctx, String key, String detail, String... args) {
        synchronized (ctx.lock()) {
            job.fail(key, detail, args);
        }
        return Outcome.FAILED;
    }

    static String tail(List<String> lines) {
        int from = Math.max(0, lines.size() - 8);
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
