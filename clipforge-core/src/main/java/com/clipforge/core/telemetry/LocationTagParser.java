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
        double lat = Double.parseDouble(m.group(1));
        double lon = Double.parseDouble(m.group(2));
        Double alt = m.group(3) == null ? null : Double.valueOf(m.group(3));
        TelemetrySample s = new TelemetrySample(Duration.ZERO, null, lat, lon, alt, null, null);
        return s.hasPosition() ? Optional.of(s) : Optional.empty();
    }
}
