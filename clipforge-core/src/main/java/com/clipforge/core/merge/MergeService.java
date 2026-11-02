package com.clipforge.core.merge;

import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.FfmpegProcess;
import com.clipforge.core.ffmpeg.FfmpegResult;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.Progress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Lossless merge with FFmpeg's concat demuxer and stream copy. Only the first video and audio stream are mapped:
 * camera-specific data streams (e.g. SigmaStar `ssmd` GPS/metadata) cannot be written by FFmpeg to MP4, MOV or MKV,
 * so they are dropped. Telemetry is exported separately (planned, see docs/PLAN.md phase 4).
 */
public final class MergeService {

    private static final Logger LOG = Logger.getLogger(MergeService.class.getName());

    private final FfmpegTools tools;

    public MergeService(FfmpegTools tools) {
        this.tools = tools;
    }

    /**
     * Runs the merge. The output must not exist yet; on failure or cancellation the partial output is deleted.
     * If the first attempt fails, it is retried once with regenerated timestamps ({@code -fflags +genpts}).
     */
    public FfmpegResult merge(MergeSpec spec, Consumer<Progress> progress, CancellationToken token) throws IOException {
        Path output = Path.of(spec.output());
        if (Files.exists(output)) {
            throw new FileAlreadyExistsException(output.toString());
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path list = Files.createTempFile("clipforge-concat-", ".txt");
        try {
            Files.writeString(list, concatList(spec.inputs()), StandardCharsets.UTF_8);
            Duration total = Duration.ofMillis(spec.totalDurationMs());
            FfmpegResult result = FfmpegProcess.run(command(spec, list, false), total, progress, token);
            if (!result.success() && !result.cancelled()) {
                LOG.warning(() -> "Merge failed, retrying with +genpts: " + result.stderrText());
                Files.deleteIfExists(output);
                FfmpegResult retry = FfmpegProcess.run(command(spec, list, true), total, progress, token);
                return finish(retry, output);
            }
            return finish(result, output);
        } finally {
            Files.deleteIfExists(list);
        }
    }

    private static FfmpegResult finish(FfmpegResult result, Path output) throws IOException {
        if (!result.success()) {
            Files.deleteIfExists(output);
        }
        return result;
    }

    /** Builds the ffmpeg command (without the progress flags added by {@link FfmpegProcess}). */
    List<String> command(MergeSpec spec, Path listFile, boolean genpts) {
        List<String> cmd = new ArrayList<>();
        cmd.add(tools.ffmpeg().toString());
        if (genpts) {
            cmd.addAll(List.of("-fflags", "+genpts"));
        }
        cmd.addAll(List.of("-f", "concat", "-safe", "0", "-i", listFile.toString()));
        cmd.addAll(List.of("-map", "0:v:0", "-map", "0:a:0?", "-ignore_unknown",
                "-c", "copy", "-avoid_negative_ts", "make_zero",
                "-movflags", spec.options().faststart() ? "+faststart" : "+frag_keyframe+empty_moov+default_base_moof"));
        cmd.add("-n"); // never overwrite
        cmd.add(spec.output());
        return cmd;
    }

    /**
     * Concat demuxer list. Paths use forward slashes and are single-quoted; a single quote inside a path is written
     * as {@code '\''} (close quote, escaped quote, reopen).
     */
    static String concatList(List<String> inputs) {
        StringBuilder sb = new StringBuilder("ffconcat version 1.0\n");
        for (String in : inputs) {
            String p = in.replace('\\', '/').replace("'", "'\\''");
            sb.append("file '").append(p).append("'\n");
        }
        return sb.toString();
    }
}
