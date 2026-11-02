package com.clipforge.core.compress;

import com.clipforge.core.ffmpeg.FfmpegTools;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Detects which encoder profiles actually work on this machine by encoding a fraction of a second of a test pattern
 * (an encoder can be compiled into FFmpeg but still fail, e.g. NVENC without an NVIDIA GPU or driver).
 */
public final class EncoderCapabilities {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private EncoderCapabilities() {
    }

    public static Set<EncoderProfile> detect(FfmpegTools tools) {
        Set<EncoderProfile> ok = EnumSet.noneOf(EncoderProfile.class);
        for (EncoderProfile p : EncoderProfile.values()) {
            if (works(tools, p)) {
                ok.add(p);
            }
        }
        return ok;
    }

    /** Preferred default: AV1 on the GPU, then the CPU encoders. */
    public static EncoderProfile preferred(Set<EncoderProfile> available) {
        for (EncoderProfile p : List.of(EncoderProfile.AV1_NVENC, EncoderProfile.SVT_AV1, EncoderProfile.HEVC_NVENC,
                EncoderProfile.X265)) {
            if (available.contains(p)) {
                return p;
            }
        }
        return null;
    }

    static boolean works(FfmpegTools tools, EncoderProfile p) {
        List<String> cmd = new ArrayList<>(List.of(tools.ffmpeg().toString(), "-hide_banner", "-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=256x144:rate=30:duration=0.5", "-pix_fmt", "yuv420p"));
        cmd.addAll(p.videoArgs(p.defaultQuality()));
        cmd.addAll(List.of("-f", "null", "-"));
        try {
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            proc.getOutputStream().close();
            if (!proc.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
