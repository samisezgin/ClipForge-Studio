package com.clipforge.core.telemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * Telemetry of one clip or of several clips joined in time order.
 *
 * @param source  parser id, e.g. {@code sigmastar}, {@code gopro}, {@code dji-srt}, {@code location-tag}
 * @param samples readings in time order
 */
public record Telemetry(String source, List<TelemetrySample> samples) {

    public static final Telemetry NONE = new Telemetry("none", List.of());

    public Telemetry {
        samples = List.copyOf(samples);
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }

    /** {@code true} if at least one sample has a valid position. */
    public boolean hasPositions() {
        return samples.stream().anyMatch(TelemetrySample::hasPosition);
    }

    public boolean hasSpeed() {
        return samples.stream().anyMatch(s -> s.speedKmh() != null);
    }

    public long positionCount() {
        return samples.stream().filter(TelemetrySample::hasPosition).count();
    }

    /** Joins the telemetry of consecutive clips; each clip's offsets are shifted by the clips before it. */
    public static Telemetry join(List<Telemetry> parts, List<java.time.Duration> clipDurations) {
        List<TelemetrySample> all = new ArrayList<>();
        java.time.Duration offset = java.time.Duration.ZERO;
        String source = "none";
        for (int i = 0; i < parts.size(); i++) {
            Telemetry t = parts.get(i);
            if (!t.isEmpty()) {
                source = t.source();
            }
            for (TelemetrySample s : t.samples()) {
                all.add(s.shifted(offset));
            }
            offset = offset.plus(clipDurations.get(i));
        }
        return new Telemetry(source, all);
    }
}
