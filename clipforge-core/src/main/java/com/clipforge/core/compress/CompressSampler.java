package com.clipforge.core.compress;

import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.FfmpegProcess;
import com.clipforge.core.ffmpeg.FfmpegResult;
import com.clipforge.core.ffmpeg.FfmpegTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Encodes a short sample from the middle of the input with the chosen settings and extrapolates the final size
 * and encoding time. Real numbers from the user's own footage beat any rule of thumb.
 */
public final class CompressSampler {

    /**
     * @param sampleSeconds    media length actually encoded
     * @param sampleBytes      size of the encoded sample (video only)
     * @param speed            media seconds per wall-clock second (with one encoder; segments may run in parallel)
     * @param estimatedBytes   extrapolated output size (video)
     * @param estimatedTime    extrapolated encoding time, taking the configured concurrency into account
     * @param bitrateKbps      video bitrate of the sample
     */
    public record Result(double sampleSeconds, long sampleBytes, double speed, long estimatedBytes,
                         Duration estimatedTime, long bitrateKbps) {

        public double ratio(long inputBytes) {
            return inputBytes <= 0 ? 0 : (double) estimatedBytes / inputBytes;
        }
    }

    private final FfmpegTools tools;

    public CompressSampler(FfmpegTools tools) {
        this.tools = tools;
    }

    public Result sample(CompressSpec spec, double seconds, CancellationToken token) throws IOException {
        double total = spec.totalDurationMs() / 1000.0;
        // sample from the middle of the timeline, inside one source (seeking across joined clips is unreliable)
        double middle = total / 2;
        CompressInput source = spec.sources().getLast();
        double localMiddle = 0;
        double offset = 0;
        for (CompressInput in : spec.sources()) {
            double d = in.durationMs() / 1000.0;
            if (middle < offset + d) {
                source = in;
                localMiddle = middle - offset;
                break;
            }
            offset += d;
        }
        double sourceSeconds = source.durationMs() / 1000.0;
        double length = Math.min(seconds, sourceSeconds);
        double from = Math.max(0, Math.min(sourceSeconds - length, localMiddle - length / 2));
        Path work = Files.createTempDirectory("clipforge-sample-");
        Path out = work.resolve("sample.mp4");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(tools.ffmpeg().toString());
            cmd.addAll(List.of("-ss", fmt(from), "-t", fmt(length), "-i", source.path()));
            cmd.addAll(List.of("-map", "0:v:0"));
            CompressOptions o = spec.options();
            if (o.spedUp()) {
                // sped-up footage has more motion per frame: sample it the way it will be encoded
                double fps = source.frameRate() > 0 ? source.frameRate() : 30;
                cmd.addAll(List.of("-vf", String.format(Locale.ROOT, "setpts=PTS/%s,fps=%.3f", o.speed(), fps)));
            }
            cmd.addAll(o.profile().videoArgs(o.quality()));
            cmd.addAll(List.of("-an", "-sn", "-dn", "-f", "mp4", "-y", out.toString()));
            long start = System.nanoTime();
            FfmpegResult r = FfmpegProcess.run(cmd, Duration.ofMillis(Math.round(length * 1000)), null, token);
            double wall = (System.nanoTime() - start) / 1e9;
            if (!r.success()) {
                throw new IOException(r.cancelled() ? "cancelled" : r.stderrText());
            }
            long bytes = Files.size(out);
            double speed = length / Math.max(0.001, wall);
            long estimated = Math.round(bytes / length * total);
            // extra parallel segments help, but less than linearly (shared GPU encoder / CPU cores)
            double parallel = 1 + (Math.max(1, spec.options().concurrency()) - 1) * (spec.options().profile().hardware() ? 0.6 : 0.3);
            Duration time = Duration.ofSeconds(Math.round(total / (speed * parallel)));
            // `length` source seconds became length/speed seconds of output (the estimate above scales alike)
            double outSeconds = length / o.speed();
            return new Result(length, bytes, speed, estimated, time, Math.round(bytes * 8 / outSeconds / 1000));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(work);
        }
    }

    private static String fmt(double s) {
        return String.format(Locale.ROOT, "%.3f", s);
    }
}
