package com.clipforge.core.compress;

import java.util.List;

/**
 * Immutable description of a compression job, stored in the project file.
 *
 * @param sources         one video file, or several clips that are joined (in this order)
 * @param output          final output path
 * @param options         encoder settings (locked once the job started)
 * @param totalDurationMs media duration of all sources together
 * @param totalBytes      size of all sources together
 * @param sourceHasAudio  whether the sources contain an audio track
 */
public record CompressSpec(List<CompressInput> sources, String output, CompressOptions options, long totalDurationMs,
                           long totalBytes, boolean sourceHasAudio) {

    public CompressSpec {
        // tolerate hand-edited or damaged project files: a job without sources fails instead of the whole load
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** Absolute source paths in order. */
    public List<String> inputs() {
        return sources.stream().map(CompressInput::path).toList();
    }

    /** Start of source {@code i} on the output timeline, in milliseconds. */
    public long offsetMs(int i) {
        long t = 0;
        for (int k = 0; k < i; k++) {
            t += sources.get(k).durationMs();
        }
        return t;
    }
}
