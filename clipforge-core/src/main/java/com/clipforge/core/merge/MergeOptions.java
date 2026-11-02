package com.clipforge.core.merge;

/**
 * @param faststart write a standard MP4 with the index at the front (better for streaming/old players).
 *                  When {@code false} (default) a fragmented MP4 is written that grows while merging and
 *                  stays playable if interrupted.
 */
public record MergeOptions(boolean faststart) {

    public static final MergeOptions DEFAULT = new MergeOptions(false);

    public String extension() {
        return "mp4";
    }
}
