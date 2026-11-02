package com.clipforge.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Application-wide constants and build information. */
public final class ClipForge {
    public static final String NAME = "ClipForge Studio";

    private static final Properties BUILD = load();

    private ClipForge() {
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = ClipForge.class.getResourceAsStream("/clipforge-build.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
            // no build information (e.g. running from an IDE without the Maven build)
        }
        return p;
    }

    /** Semantic version from the POM, e.g. {@code 0.4.0}, or "dev". */
    public static String version() {
        return BUILD.getProperty("git.build.version", "dev");
    }

    /** Short commit SHA of the build, with {@code -dirty} for uncommitted changes; empty if unknown. */
    public static String commit() {
        String sha = BUILD.getProperty("git.commit.id.abbrev", "");
        return sha.isEmpty() ? "" : sha + ("true".equals(BUILD.getProperty("git.dirty")) ? "-dirty" : "");
    }

    /** Display form used in the About dialog, logs and release titles, e.g. {@code v0.4.0 (ac32ef1)}. */
    public static String displayVersion() {
        String c = commit();
        return "v" + version() + (c.isEmpty() ? "" : " (" + c + ")");
    }
}