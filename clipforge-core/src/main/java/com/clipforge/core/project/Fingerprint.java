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
            return Files.isRegularFile(p) && Files.size(p) == size
                    && Files.getLastModifiedTime(p).toMillis() == modifiedMillis;
        } catch (IOException e) {
            return false;
        }
    }
}
