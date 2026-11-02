package com.clipforge.core.model;

/** Where a clip's start time came from, in order of preference. */
public enum TimestampSource {
    FILE_NAME,
    CREATION_TIME,
    FILE_MODIFIED
}
