package com.clipforge.core.components;

import java.util.List;
import java.util.Locale;

/**
 * Something ClipForge downloads on demand instead of shipping it: FFmpeg on Windows, detection models. Listed in
 * {@code components.json} (like a requirements file) with a fixed URL and SHA-256, so only exactly that file is used.
 *
 * @param id      stable key, also the folder name
 * @param name    shown to the user
 * @param version part of the install folder: a new version installs next to the old one
 * @param url     download address (redirects are followed)
 * @param sha256  checksum of the downloaded file; anything else is rejected
 * @param size    expected download size in bytes (progress, free-space check)
 * @param archive {@code file} (the download is the file) or {@code zip} (the listed files are taken from it)
 * @param files   installed file names; for a zip, entries ending in these names
 * @param os      {@code any} or {@code windows}
 * @param when    {@code always}, or {@code noFfmpeg} (only when no FFmpeg is installed otherwise)
 * @param license shown in the About/components overview
 */
public record Component(String id, String name, String version, String url, String sha256, long size,
                        String archive, List<String> files, String os, String when, String license) {

    public Component {
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** Whether the component applies to this operating system. */
    public boolean forThisSystem() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return "any".equals(os) || ("windows".equals(os) && osName.startsWith("windows"));
    }

    public boolean isZip() {
        return "zip".equals(archive);
    }
}
