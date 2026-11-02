package com.clipforge.core.compress;

import com.clipforge.core.merge.IncompatibleClipsException;
import com.clipforge.core.merge.MergePlanner;
import com.clipforge.core.model.Clip;
import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.util.OutputNaming;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Builds {@link CompressSpec}s from a file or from a clip selection. */
public final class CompressPlanner {

    private CompressPlanner() {
    }

    /** Compress one existing video file. */
    public static CompressSpec forFile(Path file, long sizeBytes, MediaInfo media, Path outputDir,
                                       CompressOptions options, Collection<Path> reserved) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        Path out = OutputNaming.unique(outputDir, stem + "_" + tag(options) + ".mp4", reserved);
        // recording start with the same rules as scanned clips: file name, creation_time, file time minus duration
        long start = recordingStart(file, media);
        return new CompressSpec(List.of(CompressInput.of(file, media, start)), out.toString(), options,
                media.duration().toMillis(), sizeBytes, media.hasAudio());
    }

    /** Name tag, e.g. {@code av1} or {@code av1_3x} for a sped-up video. */
    private static String tag(CompressOptions o) {
        return o.profile().shortName() + (o.spedUp() ? "_" + o.speedLabel() : "");
    }

    private static long recordingStart(Path file, MediaInfo media) {
        try {
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            var resolved = new com.clipforge.core.scan.TimestampResolver(zone).resolve(
                    com.clipforge.core.scan.TimestampResolver.parseName(file.getFileName().toString()), media,
                    java.nio.file.Files.getLastModifiedTime(file).toInstant());
            return resolved.start().atZone(zone).toInstant().toEpochMilli();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    /**
     * Join and compress clips in one go (no intermediate merged file). The clips must share codec and resolution,
     * like a lossless merge.
     */
    public static CompressSpec forClips(List<Clip> clips, Path outputDir, CompressOptions options,
                                        Collection<Path> reserved) throws IncompatibleClipsException {
        MergePlanner.requireCompatible(clips);
        String base = OutputNaming.baseName(clips, tag(options), "mp4");
        Path out = OutputNaming.unique(outputDir, base, reserved);
        return new CompressSpec(clips.stream().map(c -> CompressInput.of(c.path(), c.media(),
                        c.start().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())).toList(),
                out.toString(), options,
                clips.stream().mapToLong(c -> c.duration().toMillis()).sum(),
                clips.stream().mapToLong(Clip::size).sum(),
                clips.stream().allMatch(c -> c.media().hasAudio()));
    }
}
