package com.clipforge.core.util;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Checks the target drive before writing large outputs. */
public final class DiskCheck {

    /** Safety margin on top of the estimated output size. */
    public static final double MARGIN = 1.05;
    public static final long FAT32_MAX_FILE = 4L * 1024 * 1024 * 1024 - 1;

    /**
     * @param usableBytes    free space available to this process
     * @param requiredBytes  estimated output size including the safety margin
     * @param fileSystem     file system type as reported by the OS (e.g. NTFS, exFAT, FAT32)
     * @param largestFile    largest single output file that will be written
     */
    public record Result(long usableBytes, long requiredBytes, String fileSystem, long largestFile) {

        public boolean enoughSpace() {
            return usableBytes >= requiredBytes;
        }

        /** {@code true} if the file system cannot store a file as large as {@code largestFile}. */
        public boolean exceedsFileSizeLimit() {
            return isFat(fileSystem) && largestFile > FAT32_MAX_FILE;
        }

        public boolean ok() {
            return enoughSpace() && !exceedsFileSizeLimit();
        }
    }

    private DiskCheck() {
    }

    /** FAT12/16/32 (Windows "FAT32", Linux "vfat"/"msdos"), but not exFAT. */
    static boolean isFat(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toLowerCase(Locale.ROOT);
        return t.matches("v?fat\\d*") || t.equals("msdos");
    }

    /**
     * @param outputDir      existing directory the output will be written to
     * @param estimatedBytes estimated total output size (before margin)
     * @param largestFile    largest single output file
     */
    public static Result check(Path outputDir, long estimatedBytes, long largestFile) throws IOException {
        Path existing = outputDir.toAbsolutePath();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent(); // the folder may not be created yet
        }
        if (existing == null) {
            throw new IOException("No existing parent for " + outputDir);
        }
        FileStore store = Files.getFileStore(existing);
        long required = (long) Math.ceil(estimatedBytes * MARGIN);
        return new Result(store.getUsableSpace(), required, store.type(), largestFile);
    }
}
