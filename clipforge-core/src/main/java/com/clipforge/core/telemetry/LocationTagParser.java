package com.clipforge.core.telemetry;

import com.clipforge.core.ffmpeg.FfmpegTools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phones store a single recording location as an ISO 6709 string in the container ({@code location},
 * {@code location-eng} or {@code com.apple.quicktime.location.ISO6709}), e.g. {@code +41.0082+028.9784+039.000/}.
 */
public final class LocationTagParser implements TelemetryParser {

    private static final Pattern ISO6709 = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)?");
    private static final List<String> TAGS = List.of("com.apple.quicktime.location.ISO6709", "location",
            "location-eng");

    @Override
    public String id() {
        return "location-tag";
    }

    @Override
    public Optional<Telemetry> parse(Path file, StreamTable streams, FfmpegTools tools) {
        for (String tag : TAGS) {
            String v = streams.formatTags().get(tag);
            if (v != null) {
                Optional<TelemetrySample> s = parseIso6709(v);
                if (s.isPresent()) {
                    return Optional.of(new Telemetry(id(), List.of(s.get())));
                }
            }
        }
        return Optional.empty();
    }

    static Optional<TelemetrySample> parseIso6709(String v) {
        Matcher m = ISO6709.matcher(v.trim());
        if (!m.lookingAt()) {
            return Optional.empty();
        }
        double lat = degrees(m.group(1), 2);
        double lon = degrees(m.group(2), 3);
        Double alt = m.group(3) == null ? null : Double.valueOf(m.group(3));
        TelemetrySample s = new TelemetrySample(Duration.ZERO, null, lat, lon, alt, null, null);
        return s.hasPosition() ? Optional.of(s) : Optional.empty();
    }

    /**
     * ISO 6709 allows ±DD.D, ±DDMM.M and ±DDMMSS.S (longitude with three degree digits); the number of digits before
     * the decimal point tells which.
     */
    static double degrees(String v, int degreeDigits) {
        double sign = v.startsWith("-") ? -1 : 1;
        String n = v.substring(1);
        int dot = n.indexOf('.');
        int intDigits = dot < 0 ? n.length() : dot;
        double value = Double.parseDouble(n);
        if (intDigits == degreeDigits + 2) { // DDMM
            double d = Math.floor(value / 100);
            return sign * (d + (value - d * 100) / 60);
        }
        if (intDigits == degreeDigits + 4) { // DDMMSS
            double d = Math.floor(value / 10000);
            double rest = value - d * 10000;
            double min = Math.floor(rest / 100);
            return sign * (d + min / 60 + (rest - min * 100) / 3600);
        }
        return sign * value;
    }
}
