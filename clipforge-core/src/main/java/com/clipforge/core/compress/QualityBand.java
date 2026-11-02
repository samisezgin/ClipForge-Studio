package com.clipforge.core.compress;

/**
 * Rough meaning of a constant-quality value, from best quality / largest files to smallest files.
 * The value ranges differ per encoder, see {@link EncoderProfile#range(QualityBand)}.
 */
public enum QualityBand {
    /** Hard to tell apart from the source. */
    TRANSPARENT,
    VERY_GOOD,
    /** Enough for dash cam and everyday footage; the defaults sit here. */
    GOOD,
    /** Visible loss of fine detail. */
    SMALL
}
