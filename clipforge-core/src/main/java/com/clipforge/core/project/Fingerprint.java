package com.clipforge.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Identifies a source file version: if size or modification time change, the file is considered changed. */
public record Fingerprint(String path, long size, long modifiedMillis) {

    public static Fingerprint of(Path p) throws IOException {
        return new Fingerprint(p.toAbsolutePath().toString(), Files.size(p), Files.getLastModifiedTime(p).toMillis());
    }

    /** @return {@code true} if the file still exists with the same size and modification time */
    public boolean matchesDisk() {
        try {
            Path p = Path.of(path);
            if (!Files.isRegularFile(p) || Files.size(p) != size) {
                return false;
            }
            long diff = Math.abs(Files.getLastModifiedTime(p).toMillis() - modifiedMillis);
            // FAT32 stores local time: Windows shows such files one hour off after a DST change; FAT also rounds
            // to 2 s. Same size and such a shift means the same file.
            return diff <= 2000 || Math.abs(diff - 3_600_000) <= 2000;
        } catch (IOException e) {
            return false;
        }
    }
}
