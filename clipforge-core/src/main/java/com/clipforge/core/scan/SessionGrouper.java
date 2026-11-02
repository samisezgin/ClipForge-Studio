package com.clipforge.core.scan;

import com.clipforge.core.model.Clip;
import com.clipforge.core.model.Session;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits clips into continuous sessions. A new session starts when
 * <ul>
 *     <li>the next clip starts later than {@code previous.start + previous.duration + tolerance}, or</li>
 *     <li>codec / resolution change (such clips cannot be stream-copied together).</li>
 * </ul>
 * Each channel (e.g. front / rear camera) is grouped separately.
 */
public final class SessionGrouper {

    public static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(5);

    private final Duration tolerance;

    public SessionGrouper(Duration tolerance) {
        this.tolerance = tolerance;
    }

    public List<Session> group(List<Clip> clips) {
        Map<String, List<Clip>> byChannel = new LinkedHashMap<>();
        for (Clip c : clips) {
            byChannel.computeIfAbsent(c.channel() == null ? "" : c.channel(), k -> new ArrayList<>()).add(c);
        }
        List<Session> sessions = new ArrayList<>();
        for (List<Clip> channelClips : byChannel.values()) {
            sessions.addAll(groupChannel(channelClips));
        }
        sessions.sort(Comparator.comparing(Session::start)
                .thenComparing(s -> s.channel() == null ? "" : s.channel()));
        return sessions;
    }

    private List<Session> groupChannel(List<Clip> clips) {
        List<Clip> sorted = new ArrayList<>(clips);
        sorted.sort(Comparator.comparing(Clip::start).thenComparingLong(Clip::sequence));
        Duration typical = typicalDuration(sorted);

        List<Session> result = new ArrayList<>();
        List<Clip> current = new ArrayList<>();
        LocalDateTime previousSessionEnd = null;
        Clip prev = null;
        LocalDateTime maxEnd = null; // running maximum, so a short overlapping clip cannot split a session
        for (Clip c : sorted) {
            if (prev != null && startsNewSession(prev, maxEnd, c)) {
                Session s = new Session(current, gap(previousSessionEnd, current.getFirst().start()));
                result.add(s);
                previousSessionEnd = s.end();
                current = new ArrayList<>();
                maxEnd = null;
            }
            current.add(c);
            prev = c;
            LocalDateTime end = c.start().plus(c.duration().isZero() ? typical : c.duration());
            maxEnd = maxEnd == null || end.isAfter(maxEnd) ? end : maxEnd;
        }
        if (!current.isEmpty()) {
            result.add(new Session(current, gap(previousSessionEnd, current.getFirst().start())));
        }
        return result;
    }

    private boolean startsNewSession(Clip prev, LocalDateTime maxEnd, Clip next) {
        if (next.start().isAfter(maxEnd.plus(tolerance))) {
            return true;
        }
        // unknown media (still copying) never forces a split on its own
        if (prev.media() == null || next.media() == null) {
            return false;
        }
        return !Objects.equals(prev.media().compatibilityKey(), next.media().compatibilityKey());
    }

    /** Median duration of readable clips; used for clips whose duration is not known yet. */
    private static Duration typicalDuration(List<Clip> clips) {
        List<Duration> known = clips.stream().map(Clip::duration).filter(d -> !d.isZero()).sorted().toList();
        return known.isEmpty() ? Duration.ZERO : known.get(known.size() / 2);
    }

    private static Duration gap(LocalDateTime previousEnd, LocalDateTime start) {
        if (previousEnd == null) {
            return null;
        }
        Duration d = Duration.between(previousEnd, start);
        return d.isNegative() ? Duration.ZERO : d;
    }
}
