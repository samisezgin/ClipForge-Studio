package com.clipforge.core.telemetry;

import com.clipforge.core.ffmpeg.FfmpegTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DJI drones write a subtitle file next to each video ({@code DJI_0001.MP4} + {@code DJI_0001.SRT}) with one entry
 * per frame or second. Several text layouts exist; the common ones are recognised:
 * {@code [latitude: 22.5] [longitude: 113.9] [rel_alt: 1.2 abs_alt: 20.1]}, {@code GPS(113.9,22.5,15)},
 * and a date line {@code 2026-11-01 12:00:00.123}.
 */
public final class DjiSrtParser implements TelemetryParser {

    private static final Pattern TIMING = Pattern.compile(
            "(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->");
    private static final Pattern LAT = Pattern.compile("latitude\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern LON = Pattern.compile("longitude\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern ABS_ALT = Pattern.compile("abs_alt\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern GPS = Pattern.compile("GPS\\s*\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)"
            + "(?:\\s*,\\s*(-?\\d+(?:\\.\\d+)?))?");
    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})(?:[.,](\\d{1,3}))?");

    private final ZoneId zone;

    public DjiSrtParser(ZoneId zone) {
        this.zone = zone;
    }

    @Override
    public String id() {
        return "dji-srt";
    }

    @Override
    public Optional<Telemetry> parse(Path file, StreamTable streams, FfmpegTools tools) throws IOException {
        Optional<Path> srt = sidecar(file);
        if (srt.isEmpty()) {
            return Optional.empty();
        }
        List<TelemetrySample> samples = parse(Files.readString(srt.get(), StandardCharsets.UTF_8));
        return samples.isEmpty() ? Optional.empty() : Optional.of(new Telemetry(id(), samples));
    }

    static Optional<Path> sidecar(Path video) {
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        for (String ext : List.of(".SRT", ".srt")) {
            Path p = video.resolveSibling(stem + ext);
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    List<TelemetrySample> parse(String text) {
        List<TelemetrySample> out = new ArrayList<>();
        for (String block : text.replace("\r", "").split("\n\\s*\n")) {
            Matcher t = TIMING.matcher(block);
            if (!t.find()) {
                continue;
            }
            Duration offset = Duration.ofHours(Long.parseLong(t.group(1))).plusMinutes(Long.parseLong(t.group(2)))
                    .plusSeconds(Long.parseLong(t.group(3))).plusMillis(Long.parseLong(pad(t.group(4))));
            Double lat = number(LAT, block, 1);
            Double lon = number(LON, block, 1);
            Double alt = number(ABS_ALT, block, 1);
            Matcher g = GPS.matcher(block);
            if ((lat == null || lon == null) && g.find()) {
                lon = Double.parseDouble(g.group(1)); // GPS(lon, lat, alt)
                lat = Double.parseDouble(g.group(2));
                alt = g.group(3) == null ? alt : Double.valueOf(g.group(3));
            }
            Instant time = null;
            Matcher d = DATE.matcher(block);
            if (d.find()) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(d.group(1),
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    if (d.group(2) != null) {
                        ldt = ldt.plusNanos(Long.parseLong(pad(d.group(2))) * 1_000_000);
                    }
                    time = ldt.atZone(zone).toInstant();
                } catch (DateTimeParseException ignored) {
                    // unusual date layout
                }
            }
            if (lat != null || time != null) {
                out.add(new TelemetrySample(offset, time, lat, lon, alt, null, null));
            }
        }
        return out;
    }

    private static String pad(String ms) {
        return (ms + "00").substring(0, 3);
    }

    private static Double number(Pattern p, String s, int group) {
        Matcher m = p.matcher(s);
        return m.find() ? Double.valueOf(m.group(group)) : null;
    }
}
