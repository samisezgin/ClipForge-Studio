package com.clipforge.core.ffmpeg;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds {@code ffmpeg} / {@code ffprobe}: an explicit directory first, then {@code PATH}, then common install
 * locations (WinGet, Chocolatey, Scoop, Homebrew).
 */
public final class FfmpegLocator {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private FfmpegLocator() {
    }

    /**
     * @param preferredDir directory configured by the user, may be {@code null}
     */
    public static Optional<FfmpegTools> locate(Path preferredDir) {
        for (Path dir : candidateDirs(preferredDir)) {
            Optional<FfmpegTools> tools = fromDir(dir);
            if (tools.isPresent()) {
                return tools;
            }
        }
        return Optional.empty();
    }

    /** Checks one directory for both executables and reads the FFmpeg version. */
    public static Optional<FfmpegTools> fromDir(Path dir) {
        if (dir == null) {
            return Optional.empty();
        }
        Path ffmpeg = dir.resolve(exe("ffmpeg"));
        Path ffprobe = dir.resolve(exe("ffprobe"));
        if (!Files.isRegularFile(ffmpeg) || !Files.isRegularFile(ffprobe)) {
            return Optional.empty();
        }
        String version = readVersion(ffmpeg);
        return version == null ? Optional.empty() : Optional.of(new FfmpegTools(ffmpeg, ffprobe, version));
    }

    private static List<Path> candidateDirs(Path preferredDir) {
        List<Path> dirs = new ArrayList<>();
        if (preferredDir != null) {
            dirs.add(preferredDir);
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String p : path.split(File.pathSeparator)) {
                if (!p.isBlank()) {
                    try {
                        dirs.add(Path.of(p.trim().replace("\"", "")));
                    } catch (RuntimeException ignored) {
                        // malformed PATH entry
                    }
                }
            }
        }
        if (WINDOWS) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                dirs.addAll(findBinDirs(Path.of(local, "Microsoft", "WinGet", "Packages")));
            }
            dirs.add(Path.of("C:\\ProgramData\\chocolatey\\bin"));
            dirs.add(Path.of(System.getProperty("user.home"), "scoop", "shims"));
            dirs.add(Path.of("C:\\ffmpeg\\bin"));
        } else {
            dirs.add(Path.of("/opt/homebrew/bin"));
            dirs.add(Path.of("/usr/local/bin"));
            dirs.add(Path.of("/usr/bin"));
        }
        return dirs;
    }

    private static List<Path> findBinDirs(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.find(root, 5,
                (p, a) -> a.isRegularFile() && p.getFileName().toString().equalsIgnoreCase(exe("ffmpeg")))) {
            return s.map(Path::getParent).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String exe(String name) {
        return WINDOWS ? name + ".exe" : name;
    }

    private static String readVersion(Path ffmpeg) {
        try {
            ProcessCapture.Output out = ProcessCapture.run(List.of(ffmpeg.toString(), "-hide_banner", "-version"),
                    Duration.ofSeconds(10));
            if (out.exitCode() != 0) {
                return null;
            }
            String first = out.stdout().lines().findFirst().orElse("");
            // "ffmpeg version 9.0-full_build-www.gyan.dev Copyright ..."
            String[] parts = first.split("\\s+");
            return parts.length >= 3 && parts[0].equals("ffmpeg") ? parts[2] : null;
        } catch (IOException e) {
            return null;
        }
    }
}
