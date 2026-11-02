package com.clipforge.core.merge;

import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicRenderer;
import com.clipforge.core.audio.MusicSettings;
import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.FfmpegProcess;
import com.clipforge.core.ffmpeg.FfmpegResult;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.util.OutputNaming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Lossless merge with FFmpeg's concat demuxer and stream copy. Only the first video and audio stream are mapped:
 * camera-specific data streams (e.g. SigmaStar {@code ssmd} GPS/metadata) cannot be written by FFmpeg to MP4, MOV or
 * MKV, so they are dropped. Their telemetry can be read and exported on the Telemetry tab.
 * <p>
 * FFmpeg writes to {@code <name>.part.mp4} next to the output (it grows and stays playable while merging); only on
 * success is it renamed to the final name, without replacing anything. ClipForge only ever deletes {@code .part}
 * files, so a finished output or a file created by someone else is never removed.
 */
public final class MergeService {

    private static final Logger LOG = Logger.getLogger(MergeService.class.getName());

    private final FfmpegTools tools;

    public MergeService(FfmpegTools tools) {
        this.tools = tools;
    }

    /** Folder for intermediate audio files of a merge with music. */
    public static Path workDir(Path output) {
        return output.resolveSibling(output.getFileName() + ".work");
    }

    /** Removes a merge job's partial output and intermediate files (cancelled, discarded or recovered job). */
    public static void discard(MergeSpec spec) {
        try {
            Path out = Path.of(spec.output());
            Files.deleteIfExists(partPath(out));
            deleteDir(workDir(out));
        } catch (IOException e) {
            LOG.warning("Cannot remove partial files of " + spec.output() + ": " + e.getMessage());
        }
    }

    /** Temporary file FFmpeg writes to: {@code a/b.mp4} → {@code a/b.part.mp4}. */
    public static Path partPath(Path output) {
        return OutputNaming.partOf(output);
    }

    /**
     * Runs the merge. The final output must not exist. A stale {@code .part} file from an interrupted run is
     * replaced. If FFmpeg fails with timestamp errors, the merge is retried once with {@code -fflags +genpts}.
     */
    public FfmpegResult merge(MergeSpec spec, Consumer<Progress> progress, CancellationToken token) throws IOException {
        Path output = Path.of(spec.output());
        if (Files.exists(output)) {
            throw new FileAlreadyExistsException(output.toString());
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Path part = partPath(output);
        Files.deleteIfExists(part);
        Path work = workDir(output);
        Path list = Files.createTempFile("clipforge-concat-", ".txt");
        try {
            Files.writeString(list, concatList(spec.inputs()), StandardCharsets.UTF_8);
            Path music = null;
            if (spec.options().audio() == AudioMode.MUSIC) {
                FfmpegResult m = renderMusic(spec, list, work, token);
                if (!m.success()) {
                    return m;
                }
                music = work.resolve("music.m4a");
            }
            Duration total = Duration.ofMillis(spec.totalDurationMs());
            Path soundtrack = music;
            FfmpegResult result = FfmpegProcess.run(command(spec, list, soundtrack, part, false), total, progress, token);
            if (!result.success() && !result.cancelled() && isTimestampProblem(result)) {
                LOG.warning(() -> "Merge failed with timestamp errors, retrying with +genpts: " + result.stderrText());
                Files.deleteIfExists(part);
                FfmpegResult retry = FfmpegProcess.run(command(spec, list, soundtrack, part, true), total, progress,
                        token);
                return finish(retry, part, output);
            }
            return finish(result, part, output);
        } finally {
            Files.deleteIfExists(list);
            try {
                deleteDir(work);
            } catch (IOException e) {
                // never let a clean-up problem hide the merge result
                LOG.warning("Cannot remove " + work + ": " + e.getMessage());
            }
        }
    }

    /** Renders the background music (and extracts the original audio first if it is mixed in). */
    private FfmpegResult renderMusic(MergeSpec spec, Path list, Path work, CancellationToken token) throws IOException {
        Files.createDirectories(work);
        MusicSettings s = spec.options().music();
        Path original = null;
        if (s.mixOriginal()) {
            original = work.resolve("original.m4a");
            FfmpegResult r = null;
            // copy if possible; PCM or other codecs that MP4 cannot hold are converted to AAC
            for (List<String> codec : List.of(List.of("-c:a", "copy"), List.of("-c:a", "aac", "-b:a", "160k"))) {
                List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg().toString(), "-f", "concat", "-safe", "0",
                        "-i", list.toString(), "-map", "0:a:0", "-vn"));
                cmd.addAll(codec);
                cmd.addAll(List.of("-f", "mp4", "-y", original.toString()));
                r = FfmpegProcess.run(cmd, Duration.ZERO, null, token);
                if (r.success() || r.cancelled()) {
                    break;
                }
            }
            if (r.cancelled()) {
                return r;
            }
            if (!r.success()) {
                if (r.stderrText().toLowerCase(Locale.ROOT).contains("no space left")) {
                    return r; // a real error, not "no original audio"
                }
                LOG.info("Source has no usable audio to mix under the music");
                original = null;
            }
        }
        return new MusicRenderer(tools).render(s, spec.totalDurationMs() / 1000.0, original, work,
                work.resolve("music.m4a"), token);
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }

    private static FfmpegResult finish(FfmpegResult result, Path part, Path output) throws IOException {
        if (!result.success()) {
            Files.deleteIfExists(part);
            return result;
        }
        // no REPLACE_EXISTING: if a file appeared under the final name meanwhile, keep both and report it
        Files.move(part, output);
        return result;
    }

    /** Only timestamp problems are worth a second full run; disk full, missing input etc. are not. */
    static boolean isTimestampProblem(FfmpegResult r) {
        String err = r.stderrText().toLowerCase(Locale.ROOT);
        if (err.contains("no space left") || err.contains("permission denied") || err.contains("error opening input")
                || err.contains("no such file")) {
            return false;
        }
        return err.contains("non-monoton") || err.contains("non monoton") || err.contains("invalid dts")
                || err.contains("invalid pts") || err.contains("timestamps are unset");
    }

    /** Builds the ffmpeg command (without the progress flags added by {@link FfmpegProcess}). */
    List<String> command(MergeSpec spec, Path listFile, Path music, Path target, boolean genpts) {
        List<String> cmd = new ArrayList<>();
        cmd.add(tools.ffmpeg().toString());
        if (genpts) {
            cmd.addAll(List.of("-fflags", "+genpts"));
        }
        cmd.addAll(List.of("-f", "concat", "-safe", "0", "-i", listFile.toString()));
        if (music != null) {
            cmd.addAll(List.of("-i", music.toString()));
        }
        cmd.addAll(List.of("-map", "0:v:0"));
        switch (spec.options().audio()) {
            case KEEP -> cmd.addAll(List.of("-map", "0:a:0?"));
            case MUSIC -> cmd.addAll(List.of("-map", "1:a:0"));
            case REMOVE -> cmd.add("-an");
        }
        cmd.addAll(List.of("-ignore_unknown",
                "-c", "copy", "-avoid_negative_ts", "make_zero",
                "-movflags", spec.options().faststart() ? "+faststart" : "+frag_keyframe+empty_moov+default_base_moof",
                "-f", "mp4", "-y", target.toString()));
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
