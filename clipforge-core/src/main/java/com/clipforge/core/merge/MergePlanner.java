package com.clipforge.core.merge;

import com.clipforge.core.model.Clip;
import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.model.Session;
import com.clipforge.core.util.OutputNaming;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns a clip selection into concrete {@link MergeSpec}s. */
public final class MergePlanner {

    private MergePlanner() {
    }

    /**
     * @param sessions  all sessions (the selection is applied inside each)
     * @param selected  absolute paths of selected clips; non-selectable clips are ignored
     * @param mode      one output per session, or everything into one file
     * @param outputDir directory for the outputs
     */
    public static List<MergeSpec> plan(List<Session> sessions, Set<Path> selected, MergeMode mode, Path outputDir,
                                       MergeOptions options) throws IncompatibleClipsException {
        return plan(sessions, selected, mode, outputDir, options, List.of());
    }

    /**
     * @param reserved output paths already claimed by queued jobs; new outputs never reuse them
     */
    public static List<MergeSpec> plan(List<Session> sessions, Set<Path> selected, MergeMode mode, Path outputDir,
                                       MergeOptions options, Collection<Path> reserved)
            throws IncompatibleClipsException {
        List<List<Clip>> groups = new ArrayList<>();
        if (mode == MergeMode.PER_SESSION) {
            for (Session s : sessions) {
                List<Clip> picked = pick(s.clips(), selected);
                if (!picked.isEmpty()) {
                    groups.add(picked);
                }
            }
        } else {
            // "all in one" still keeps camera channels apart: front and rear clips must never interleave
            Map<String, List<Clip>> byChannel = new LinkedHashMap<>();
            for (Session s : sessions) {
                for (Clip c : pick(s.clips(), selected)) {
                    byChannel.computeIfAbsent(c.channel() == null ? "" : c.channel(), k -> new ArrayList<>()).add(c);
                }
            }
            for (List<Clip> all : byChannel.values()) {
                all.sort(Comparator.comparing(Clip::start).thenComparingLong(Clip::sequence));
                groups.add(all);
            }
        }
        for (List<Clip> g : groups) {
            requireCompatible(g);
        }
        List<MergeSpec> specs = new ArrayList<>();
        List<Path> taken = new ArrayList<>(reserved);
        for (List<Clip> g : groups) {
            Path out = OutputNaming.unique(outputDir, OutputNaming.baseName(g, "merged", options.extension()), taken);
            taken.add(out);
            specs.add(new MergeSpec(
                    g.stream().map(c -> c.path().toString()).toList(),
                    out.toString(),
                    options,
                    g.stream().mapToLong(c -> c.duration().toMillis()).sum(),
                    g.stream().mapToLong(Clip::size).sum()));
        }
        return specs;
    }

    private static List<Clip> pick(List<Clip> clips, Set<Path> selected) {
        return clips.stream().filter(Clip::selectable).filter(c -> selected.contains(c.path())).toList();
    }

    /** All clips must share codec and resolution to be stream-copied together. */
    public static void requireCompatible(List<Clip> clips) throws IncompatibleClipsException {
        Set<String> keys = new LinkedHashSet<>();
        for (Clip c : clips) {
            MediaInfo m = c.media();
            keys.add(m.videoCodec() + " " + m.resolution() + (m.hasAudio() ? " + " + m.audioCodec() : ""));
        }
        if (keys.size() > 1) {
            throw new IncompatibleClipsException(List.copyOf(keys));
        }
    }
}
