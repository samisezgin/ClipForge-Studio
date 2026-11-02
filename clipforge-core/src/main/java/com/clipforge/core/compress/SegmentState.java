package com.clipforge.core.compress;

/** Lifecycle of one segment; see docs/RECOVERY.md. */
public enum SegmentState {
    PENDING,
    ENCODING,
    ENCODED,
    APPENDED
}
