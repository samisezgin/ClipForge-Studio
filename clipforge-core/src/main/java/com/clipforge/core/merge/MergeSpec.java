package com.clipforge.core.merge;

import java.util.List;

/**
 * Everything needed to (re)run one merge, in a form that is stored in the project file.
 *
 * @param inputs          absolute input paths in output order
 * @param output          absolute output path
 * @param options         merge options
 * @param totalDurationMs sum of input durations, for progress reporting
 * @param totalBytes      sum of input sizes, for the disk space check
 */
public record MergeSpec(List<String> inputs, String output, MergeOptions options, long totalDurationMs,
                        long totalBytes) {

    public MergeSpec {
        inputs = List.copyOf(inputs);
    }
}
