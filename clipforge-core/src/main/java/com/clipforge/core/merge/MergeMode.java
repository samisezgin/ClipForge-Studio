package com.clipforge.core.merge;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum MergeMode {
    /** One output file per continuous session (default). */
    @JsonEnumDefaultValue
    PER_SESSION,
    /** All selected clips, across sessions and days, into a single file. */
    ALL_IN_ONE,
    /**
     * Any selected videos, in the order the user chose, into one file. Videos of different formats are re-encoded
     * to the resolution and frame rate of the first one.
     */
    FREE
}
