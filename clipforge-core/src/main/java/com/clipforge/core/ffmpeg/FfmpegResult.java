package com.clipforge.core.ffmpeg;

import java.util.List;

/**
 * Outcome of an FFmpeg run.
 *
 * @param exitCode   process exit code
 * @param cancelled  {@code true} if the run was stopped through a {@link CancellationToken}
 * @param stderrTail last lines written to stderr (errors / warnings)
 */
public record FfmpegResult(int exitCode, boolean cancelled, List<String> stderrTail) {

    public boolean success() {
        return exitCode == 0 && !cancelled;
    }

    public String stderrText() {
        return String.join("\n", stderrTail);
    }
}
