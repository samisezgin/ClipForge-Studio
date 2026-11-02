package com.clipforge.core.merge;

public enum MergeMode {
    /** One output file per continuous session (default). */
    @com.fasterxml.jackson.annotation.JsonEnumDefaultValue
    PER_SESSION,
    /** All selected clips, across sessions and days, into a single file. */
    ALL_IN_ONE
}
