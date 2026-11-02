package com.clipforge.core.model;

public enum ClipStatus {
    /** Fully readable and selectable. */
    READY,
    /** Modified very recently or not yet readable: most likely still being copied. */
    COPYING,
    /** Old file that ffprobe cannot read: damaged or not a video. */
    UNREADABLE;

    public boolean selectable() {
        return this == READY;
    }
}
