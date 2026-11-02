package com.clipforge.core;

import com.clipforge.core.ffmpeg.FfmpegLocator;
import com.clipforge.core.ffmpeg.FfmpegTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Generates small synthetic clips with FFmpeg's lavfi sources. Tests are skipped when FFmpeg is missing. */
public final class FfmpegTestSupport {

    private static final Optional<FfmpegTools> TOOLS = FfmpegLocator.locate(null);

    private FfmpegTestSupport() {
    }

    public static FfmpegTools requireFfmpeg() {
        assumeTrue(TOOLS.isPresent(), "FFmpeg not installed - skipping integration test");
        return TOOLS.get();
    }

    /**
     * Writes an H.264 + AAC clip with a test pattern, a sine tone and an extra data-like timecode track,
     * then backdates its modification time so the scanner treats it as fully copied.
     */
    public static Path makeClip(FfmpegTools t, Path file, double seconds, String size) throws IOException {
        List<String> cmd = List.of(t.ffmpeg().toString(), "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=30:duration=" + seconds,
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=" + seconds,
                "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ac", "1", "-shortest", file.toString());
        run(cmd);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3600)));
        return file;
    }

    public static void run(List<String> cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IOException("ffmpeg failed: " + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
