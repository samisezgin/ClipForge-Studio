package com.clipforge.core.telemetry;

import com.clipforge.core.FfmpegTestSupport;
import com.clipforge.core.ffmpeg.FfmpegTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Telemetry tab shows only for videos with positions or speeds; the check must be cheap and correct. */
class TelemetryAvailabilityTest {

    @TempDir
    Path tmp;

    @Test
    void locationTagCountsAPlainVideoDoesNot() throws Exception {
        FfmpegTools tools = FfmpegTestSupport.requireFfmpeg();
        Path plain = FfmpegTestSupport.makeClip(tools, tmp.resolve("plain.mp4"), 2, "160x90");
        Path tagged = tmp.resolve("phone.mp4");
        FfmpegTestSupport.run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y", "-i", plain.toString(),
                "-c", "copy", "-metadata", "location=+41.0082+028.9784/", tagged.toString()));
        TelemetryService s = new TelemetryService(tools, ZoneOffset.UTC);
        assertFalse(s.hasData(plain, Duration.ofSeconds(2)), "no telemetry in a plain video");
        assertTrue(s.hasData(tagged, Duration.ofSeconds(2)), "a phone location tag is telemetry");
    }
}
