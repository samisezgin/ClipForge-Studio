package com.clipforge.core.util;

import com.clipforge.core.model.Clip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Builds output file names such as {@code 20261101_2337-2357_F_merged.mp4}. */
public final class OutputNaming {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmm");
    private static final DateTimeFormatter TIME_SECONDS = DateTimeFormatter.ofPattern("HHmmss");

    private OutputNaming() {
    }

    /**
     * @param clips     clips in output order (non-empty)
     * @param suffix    e.g. {@code merged}
     * @param extension without dot, e.g. {@code mp4}
     */
    public static String baseName(List<Clip> clips, String suffix, String extension) {
        LocalDateTime start = clips.getFirst().start();
        LocalDateTime end = clips.getLast().end();
        // short recordings that start and end in the same minute get seconds, e.g. 100000-100006
        DateTimeFormatter time = TIME.format(start).equals(TIME.format(end))
                && start.toLocalDate().equals(end.toLocalDate()) ? TIME_SECONDS : TIME;
        StringBuilder sb = new StringBuilder(DAY.format(start)).append('_').append(time.format(start)).append('-');
        if (!start.toLocalDate().equals(end.toLocalDate())) {
            sb.append(DAY.format(end)).append('_');
        }
        sb.append(time.format(end));
        String channel = clips.getFirst().channel();
        if (channel != null && clips.stream().allMatch(c -> Objects.equals(channel, c.channel()))) {
            sb.append('_').append(channel);
        }
        return sb.append('_').append(suffix).append('.').append(extension).toString();
    }

    /**
     * Returns a path in {@code dir} that neither exists on disk nor is in {@code reserved}, appending
     * {@code " (2)"}, {@code " (3)"}, ... when needed.
     */
    public static Path unique(Path dir, String fileName, Collection<Path> reserved) {
        Set<Path> taken = Set.copyOf(reserved);
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        Path candidate = dir.resolve(fileName);
        for (int i = 2; Files.exists(candidate) || taken.contains(candidate); i++) {
            candidate = dir.resolve(stem + " (" + i + ")" + ext);
        }
        return candidate;
    }
}
