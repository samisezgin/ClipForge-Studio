package com.clipforge.core.ffmpeg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Finds a working hardware video decoder for FFmpeg's {@code -hwaccel}: NVIDIA ({@code cuda}, NVDEC), any Windows
 * GPU - AMD and Intel included - ({@code d3d11va}), Intel Quick Sync ({@code qsv}), VA-API on Linux, VideoToolbox
 * on macOS. Each candidate decodes a short test clip; FFmpeg silently falls back to software when a hardware
 * decoder fails to start, so its error output is checked as well. The result is cached per FFmpeg.
 * <p>
 * Without {@code -hwaccel_output_format} the decoded frames are copied back to main memory, which is what raw-frame
 * processing (hiding plates and faces) needs.
 */
public final class HardwareDecode {

    private static final Logger LOG = Logger.getLogger(HardwareDecode.class.getName());
    private static final Map<Path, Optional<String>> CACHE = new ConcurrentHashMap<>();

    private HardwareDecode() {
    }

    /** The {@code -hwaccel} value to use, or empty for software decoding. */
    public static Optional<String> of(FfmpegTools tools) {
        // -Dclipforge.hwdec=off (or a decoder name) overrides the detection, e.g. for comparisons
        String forced = System.getProperty("clipforge.hwdec");
        if (forced != null) {
            return "off".equals(forced) ? Optional.empty() : Optional.of(forced);
        }
        return CACHE.computeIfAbsent(tools.ffmpeg(), p -> detect(tools));
    }

    /** {@code -hwaccel <name>}, or nothing. */
    public static List<String> args(FfmpegTools tools) {
        return of(tools).map(h -> List.of("-hwaccel", h)).orElse(List.of());
    }

    static List<String> candidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return List.of("cuda", "d3d11va", "qsv");
        }
        if (os.contains("mac")) {
            return List.of("videotoolbox");
        }
        return List.of("cuda", "vaapi");
    }

    private static Optional<String> detect(FfmpegTools tools) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("clipforge-hwdec-");
            Path clip = dir.resolve("test.mp4");
            // HEVC like most cameras; 640x360 is above every decoder's minimum size
            ProcessCapture.Output made = ProcessCapture.run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=1", "-c:v", "libx265",
                    "-preset", "ultrafast", "-pix_fmt", "yuv420p", clip.toString()), Duration.ofSeconds(30));
            if (made.exitCode() != 0 || !Files.isRegularFile(clip)) {
                return Optional.empty();
            }
            for (String h : candidates()) {
                ProcessCapture.Output out = ProcessCapture.runWithStderr(List.of(tools.ffmpeg().toString(),
                        "-v", "error", "-hwaccel", h, "-i", clip.toString(), "-f", "null", "-"), Duration.ofSeconds(20));
                String err = out.stdout().toLowerCase(Locale.ROOT);
                boolean failed = out.exitCode() != 0 || err.contains("failed") || err.contains("error")
                        || err.contains("not supported");
                if (!failed) {
                    LOG.info("Hardware decoding: " + h);
                    return Optional.of(h);
                }
            }
            LOG.info("No hardware decoder works; decoding on the CPU");
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } finally {
            if (dir != null) {
                try (var files = Files.list(dir)) {
                    for (Path f : files.toList()) {
                        Files.deleteIfExists(f);
                    }
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // temp folder; harmless
                }
            }
        }
    }
}
