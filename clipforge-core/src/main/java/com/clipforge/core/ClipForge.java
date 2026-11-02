package com.clipforge.core;

/** Application-wide constants. */
public final class ClipForge {
    public static final String NAME = "ClipForge Studio";

    private ClipForge() {
    }

    /** Version from the jar manifest, or "dev" when running from sources. */
    public static String version() {
        String v = ClipForge.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
