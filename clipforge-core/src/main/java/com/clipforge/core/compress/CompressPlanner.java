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
        Path out = OutputNaming.unique(outputDir, stem + "_" + options.profile().shortName() + ".mp4", reserved);
        return new CompressSpec(List.of(file.toAbsolutePath().toString()), out.toString(), options,
                media.duration().toMillis(), sizeBytes, media.hasAudio());
    }

    /**
     * Join and compress clips in one go (no intermediate merged file). The clips must share codec and resolution,
     * like a lossless merge.
     */
    public static CompressSpec forClips(List<Clip> clips, Path outputDir, CompressOptions options,
                                        Collection<Path> reserved) throws IncompatibleClipsException {
        MergePlanner.requireCompatible(clips);
        String base = OutputNaming.baseName(clips, options.profile().shortName(), "mp4");
        Path out = OutputNaming.unique(outputDir, base, reserved);
        return new CompressSpec(clips.stream().map(c -> c.path().toString()).toList(), out.toString(), options,
                clips.stream().mapToLong(c -> c.duration().toMillis()).sum(),
                clips.stream().mapToLong(Clip::size).sum(),
                clips.stream().allMatch(c -> c.media().hasAudio()));
    }
}
