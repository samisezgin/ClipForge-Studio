package com.clipforge.core.scan;

import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.model.TimestampSource;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Determines when a clip started recording: from the file name, then the {@code creation_time} tag, then the file
 * modification time (minus the clip duration, because cameras usually close the file at the end of the clip).
 */
public final class TimestampResolver {

    /** A file-name pattern and how to turn a match into a {@link ParsedName}. */
    private record NamePattern(Pattern regex, Function<Matcher, ParsedName> extractor) {
    }

    private static final List<NamePattern> PATTERNS = List.of(
            // Dash cams (Novatek / SigmaStar / Viofo style): NO20261101-233933-004116F, EV20261101_120000_000012R
            new NamePattern(Pattern.compile("^[A-Za-z]{0,4}(\\d{8})[-_](\\d{6})(?:[-_](\\d+))?([A-Za-z])?$"),
                    m -> new ParsedName(dateTime(m.group(1), m.group(2)), upper(m.group(4)), seq(m.group(3)))),
            // Phones: VID_20261101_233933, PXL_20261101_233933123, IMG_20261101_233933
            new NamePattern(Pattern.compile("^(?:VID|PXL|IMG|MVIMG|video)[-_](\\d{8})[-_](\\d{6})\\d*(?:[-_~].*)?$",
                    Pattern.CASE_INSENSITIVE),
                    m -> new ParsedName(dateTime(m.group(1), m.group(2)), null, -1)),
            // GoPro chapters: GX010042 / GH020042 -> file 0042, chapter 01/02; no time in the name
            new NamePattern(Pattern.compile("^G[HXOPL](\\d{2})(\\d{4})$", Pattern.CASE_INSENSITIVE),
                    m -> new ParsedName(null, null, Long.parseLong(m.group(2)) * 100 + Long.parseLong(m.group(1)))),
            // Generic: 2026-11-01 23-39-33, 2026-11-01_23.39.33, 20261101T233933, Screen Recording 2026-11-01 at 23.39.33
            new NamePattern(Pattern.compile(
                    ".*?(\\d{4})-?(\\d{2})-?(\\d{2})(?:[ _T-]|\\sat\\s)(\\d{2})[-.:_]?(\\d{2})[-.:_]?(\\d{2}).*"),
                    m -> new ParsedName(dateTime(m.group(1) + m.group(2) + m.group(3),
                            m.group(4) + m.group(5) + m.group(6)), null, -1))
    );

    /** The start time and where it came from. */
    public record Resolved(LocalDateTime start, TimestampSource source) {
    }

    private final ZoneId zone;

    public TimestampResolver(ZoneId zone) {
        this.zone = zone;
    }

    /** Parses a file name (with or without extension). */
    public static ParsedName parseName(String fileName) {
        String base = stripExtension(fileName);
        for (NamePattern p : PATTERNS) {
            Matcher m = p.regex().matcher(base);
            if (m.matches()) {
                ParsedName parsed = p.extractor().apply(m);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return ParsedName.EMPTY;
    }

    public Resolved resolve(ParsedName name, MediaInfo media, Instant modified) {
        if (name.start() != null) {
            return new Resolved(name.start(), TimestampSource.FILE_NAME);
        }
        if (media != null && media.creationTime() != null) {
            return new Resolved(LocalDateTime.ofInstant(media.creationTime(), zone), TimestampSource.CREATION_TIME);
        }
        Instant start = media != null && media.duration() != null ? modified.minus(media.duration()) : modified;
        return new Resolved(LocalDateTime.ofInstant(start, zone), TimestampSource.FILE_MODIFIED);
    }

    private static LocalDateTime dateTime(String yyyymmdd, String hhmmss) {
        try {
            return LocalDateTime.of(
                    Integer.parseInt(yyyymmdd.substring(0, 4)),
                    Integer.parseInt(yyyymmdd.substring(4, 6)),
                    Integer.parseInt(yyyymmdd.substring(6, 8)),
                    Integer.parseInt(hhmmss.substring(0, 2)),
                    Integer.parseInt(hhmmss.substring(2, 4)),
                    Integer.parseInt(hhmmss.substring(4, 6)));
        } catch (DateTimeException | NumberFormatException e) {
            return null; // digits that only look like a date
        }
    }

    private static long seq(String s) {
        return s == null ? -1 : Long.parseLong(s);
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
