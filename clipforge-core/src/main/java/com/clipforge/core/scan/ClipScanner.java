package com.clipforge.core.scan;

import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.model.Clip;
import com.clipforge.core.model.ClipStatus;
import com.clipforge.core.model.MediaInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Finds video files in one or more folders and probes them in parallel.
 * Files that are still being written (recently modified or unreadable but recent) are reported as
 * {@link ClipStatus#COPYING} so they cannot be selected until a rescan.
 */
public final class ClipScanner {

    public static final Set<String> VIDEO_EXTENSIONS =
            Set.of("mp4", "mov", "m4v", "mkv", "avi", "ts", "mts", "m2ts", "webm");

    /** A file modified more recently than this is considered still being copied. */
    public static final Duration COPYING_THRESHOLD = Duration.ofSeconds(30);

    private final ProbeService probe;
    private final TimestampResolver timestamps;
    private final Clock clock;
    private final int parallelism;

    public ClipScanner(ProbeService probe, TimestampResolver timestamps, Clock clock) {
        this.probe = probe;
        this.timestamps = timestamps;
        this.clock = clock;
        this.parallelism = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * Scans the folders (non-recursively) and returns clips sorted by channel, then start time.
     *
     * @param progress called with (done, total) after each probed file, may be {@code null}
     */
    public List<Clip> scan(List<Path> folders, BiConsumer<Integer, Integer> progress) throws IOException {
        List<Path> files = listVideos(folders);
        AtomicInteger done = new AtomicInteger();
        Object progressLock = new Object();
        int[] reported = {0};
        List<Clip> clips = new ArrayList<>(files.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(parallelism)) {
            List<Future<Clip>> futures = new ArrayList<>(files.size());
            for (Path f : files) {
                futures.add(pool.submit(() -> {
                    Clip c = scanFile(f);
                    if (progress != null) {
                        int n = done.incrementAndGet();
                        synchronized (progressLock) { // keep reported values monotonic
                            if (n > reported[0]) {
                                reported[0] = n;
                                progress.accept(n, files.size());
                            }
                        }
                    }
                    return c;
                }));
            }
            for (Future<Clip> fu : futures) {
                try {
                    Clip c = fu.get();
                    if (c != null) {
                        clips.add(c);
                    }
                } catch (ExecutionException e) {
                    throw new IOException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Scan interrupted", e);
                }
            }
        }
        clips.sort(Comparator.comparing((Clip c) -> c.channel() == null ? "" : c.channel())
                .thenComparing(Clip::start)
                .thenComparingLong(Clip::sequence)
                .thenComparing(c -> c.path().toString()));
        return markDuplicates(clips);
    }

    /**
     * The same recording found in two folders (e.g. SD card and a backup) would otherwise be merged twice.
     * The first occurrence (in folder order) stays, later ones become {@link ClipStatus#DUPLICATE}.
     */
    static List<Clip> markDuplicates(List<Clip> clips) {
        Set<String> seen = new java.util.HashSet<>();
        List<Clip> result = new ArrayList<>(clips.size());
        for (Clip c : clips) {
            String key = c.channel() + "|" + c.start() + "|" + c.size();
            if (c.status() == ClipStatus.READY && !seen.add(key)) {
                result.add(new Clip(c.path(), c.size(), c.modified(), c.start(), c.startSource(), c.channel(),
                        c.sequence(), c.media(), ClipStatus.DUPLICATE));
            } else {
                result.add(c);
            }
        }
        return result;
    }

    /** Probes one file; returns {@code null} if it vanished in the meantime. */
    public Clip scanFile(Path file) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
        Instant modified = attrs.lastModifiedTime().toInstant();
        Duration age = Duration.between(modified, clock.instant());
        // a future mtime (camera clock ahead, UTC/local mix-up on FAT cards) is not "being copied"
        boolean recent = !age.isNegative() && age.compareTo(COPYING_THRESHOLD) < 0;
        Optional<MediaInfo> media = probe.probe(file);
        ClipStatus status;
        if (media.isEmpty() || !media.get().hasVideo()) {
            status = recent ? ClipStatus.COPYING : ClipStatus.UNREADABLE;
        } else {
            status = recent ? ClipStatus.COPYING : ClipStatus.READY;
        }
        ParsedName name = TimestampResolver.parseName(file.getFileName().toString());
        MediaInfo info = media.orElse(null);
        TimestampResolver.Resolved start = timestamps.resolve(name, info, modified);
        return new Clip(file.toAbsolutePath(), attrs.size(), modified, start.start(), start.source(),
                name.channel(), name.sequence(), info, status);
    }

    private static List<Path> listVideos(List<Path> folders) throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> s = Files.list(folder)) {
                s.filter(Files::isRegularFile).filter(ClipScanner::isVideo)
                        .map(Path::toAbsolutePath).forEach(files::add);
            }
        }
        return new ArrayList<>(files);
    }

    public static boolean isVideo(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 && VIDEO_EXTENSIONS.contains(n.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
