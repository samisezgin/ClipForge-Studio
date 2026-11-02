package com.clipforge.core.merge;

import java.util.List;

/** Thrown when clips with different codecs/resolutions would have to be stream-copied into one file. */
public final class IncompatibleClipsException extends Exception {

    private final List<String> formats;

    public IncompatibleClipsException(List<String> formats) {
        super("Selected clips have different formats and cannot be merged without re-encoding: " + formats);
        this.formats = List.copyOf(formats);
    }

    /** The distinct formats found, e.g. {@code hevc 2304x1296}. */
    public List<String> formats() {
        return formats;
    }
}
