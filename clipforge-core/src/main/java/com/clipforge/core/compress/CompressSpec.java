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
 * @param sourceHasAudio  whether the output gets the sources' audio (any of them has a track)
 * @param normalize       common picture format for joining videos of different formats; {@code null} when all
 *                        sources already share one
 */
public record CompressSpec(List<CompressInput> sources, String output, CompressOptions options, long totalDurationMs,
                           long totalBytes, boolean sourceHasAudio, Normalize normalize) {

    /**
     * Target format of a free merge: every source is scaled (aspect kept, black borders where needed), converted to
     * this frame rate and to 8-bit 4:2:0, so its encoded segments can be appended to the others.
     */
    public record Normalize(int width, int height, double fps) {
    }

    public CompressSpec(List<CompressInput> sources, String output, CompressOptions options, long totalDurationMs,
                        long totalBytes, boolean sourceHasAudio) {
        this(sources, output, options, totalDurationMs, totalBytes, sourceHasAudio, null);
    }

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
