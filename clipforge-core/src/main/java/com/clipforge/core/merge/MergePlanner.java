package com.clipforge.core.merge;

import com.clipforge.core.model.Clip;
import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.model.Session;
import com.clipforge.core.util.OutputNaming;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
        return plan(sessions, selected, mode, outputDir, options, reserved, List.of());
    }

    /**
     * @param order the user's order for {@link MergeMode#FREE}; clips not in it follow by recording time
     */
    public static List<MergeSpec> plan(List<Session> sessions, Set<Path> selected, MergeMode mode, Path outputDir,
                                       MergeOptions options, Collection<Path> reserved, List<Path> order)
            throws IncompatibleClipsException {
        List<List<Clip>> groups = new ArrayList<>();
        if (mode == MergeMode.FREE) {
            List<Clip> all = ordered(sessions, selected, order);
            if (!all.isEmpty()) {
                groups.add(all);
            }
        } else if (mode == MergeMode.PER_SESSION) {
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

    /**
     * The selected clips of all sessions in the user's order: listed paths first, as listed; the rest by recording
     * time (e.g. clips selected after the order was set).
     */
    public static List<Clip> ordered(List<Session> sessions, Set<Path> selected, List<Path> order) {
        List<Clip> picked = new ArrayList<>();
        for (Session s : sessions) {
            picked.addAll(pick(s.clips(), selected));
        }
        Map<Path, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            rank.putIfAbsent(order.get(i), i);
        }
        picked.sort(Comparator.comparingInt((Clip c) -> rank.getOrDefault(c.path(), Integer.MAX_VALUE))
                .thenComparing(Clip::start).thenComparingLong(Clip::sequence)
                .thenComparing(c -> c.path().toString()));
        return picked;
    }

    /** {@code true} if the clips can be stream-copied together (same codec, resolution and audio format). */
    public static boolean compatible(List<Clip> clips) {
        try {
            requireCompatible(clips);
            return true;
        } catch (IncompatibleClipsException e) {
            return false;
        }
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
