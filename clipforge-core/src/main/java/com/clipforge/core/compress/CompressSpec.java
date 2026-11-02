package com.clipforge.core.compress;

import java.util.List;
import java.util.Objects;

/**
 * Immutable description of a compression job, stored in the project file.
 *
 * @param inputs          one video file, or several clips that are joined first (in this order)
 * @param output          final output path
 * @param options         encoder settings (locked once the job started)
 * @param totalDurationMs media duration of all inputs together
 * @param totalBytes      size of all inputs together
 * @param sourceHasAudio  whether the inputs contain an audio track
 */
public record CompressSpec(List<String> inputs, String output, CompressOptions options, long totalDurationMs,
                           long totalBytes, boolean sourceHasAudio) {

    public CompressSpec {
        inputs = List.copyOf(inputs);
    }

    /** Fingerprint of everything that must not change while segments of this job exist. */
    public String settingsHash() {
        return Integer.toHexString(Objects.hash(inputs, options));
    }
}
