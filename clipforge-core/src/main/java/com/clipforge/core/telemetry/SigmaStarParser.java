package com.clipforge.core.telemetry;

import com.clipforge.core.ffmpeg.FfmpegTools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SigmaStar dash cam metadata ({@code ssmd} data track, handler "SStarMeta"): one 32-byte packet per second.
 * <pre>
 *  0..7   latitude  (double, little endian)   4294967295.0 = no GPS fix
 *  8..15  longitude (double, little endian)
 * 16..21  unknown (0xFF on cameras without a GPS module; speed/heading are not decoded)
 * 22..27  camera clock: year-1900, month (0-based), day, hour, minute, second
 * </pre>
 * Coordinates above 90/180 are interpreted as NMEA {@code ddmm.mmmm}. Reverse engineered from real footage;
 * cameras with a GPS module may need adjustments (contributions welcome).
 */
public final class SigmaStarParser implements TelemetryParser {

    private static final double NO_FIX = 4294967295.0;

    private final ZoneId zone;

    public SigmaStarParser(ZoneId zone) {
        this.zone = zone;
    }

    @Override
    public String id() {
        return "sigmastar";
    }

    @Override
    public Optional<Telemetry> parse(Path file, StreamTable streams, FfmpegTools tools) throws IOException {
        List<StreamTable.Stream> meta = streams.streams().stream()
                .filter(s -> s.codecTag().equals("ssmd") || s.handler().equals("SStarMeta")).toList();
        // the cameras write a one-packet header track and a per-second track; use the one with more packets
        List<StreamTable.Packet> best = List.of();
        for (StreamTable.Stream s : meta) {
            List<StreamTable.Packet> packets = StreamTable.packets(file, s.index(), tools);
            if (packets.size() > best.size()) {
                best = packets;
            }
        }
        List<TelemetrySample> samples = new ArrayList<>();
        for (StreamTable.Packet p : best) {
            decode(p.data(), Duration.ofMillis(Math.round(p.time() * 1000))).ifPresent(samples::add);
        }
        return samples.isEmpty() ? Optional.empty() : Optional.of(new Telemetry(id(), samples));
    }

    /**
     * Dash cams without a GPS module write only their clock: that is not shown as telemetry. A few packets at the
     * start, middle and end decide whether there is a GPS fix.
     */
    @Override
    public boolean hasData(Path file, StreamTable streams, FfmpegTools tools, Duration duration) throws IOException {
        for (StreamTable.Stream s : streams.streams()) {
            if (!s.codecTag().equals("ssmd") && !s.handler().equals("SStarMeta")) {
                continue;
            }
            for (StreamTable.Packet p : StreamTable.samplePackets(file, s.index(), tools, duration)) {
                if (decode(p.data(), Duration.ZERO).filter(TelemetrySample::hasPosition).isPresent()) {
                    return true;
                }
            }
        }
        return false;
    }

    Optional<TelemetrySample> decode(byte[] d, Duration offset) {
        if (d.length < 28) {
            return Optional.empty();
        }
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        Double lat = coordinate(b.getDouble(0), 90);
        Double lon = coordinate(b.getDouble(8), 180);
        Instant time = null;
        try {
            LocalDateTime t = LocalDateTime.of(1900 + (d[22] & 0xff), (d[23] & 0xff) + 1, d[24] & 0xff,
                    d[25] & 0xff, d[26] & 0xff, d[27] & 0xff);
            time = t.atZone(zone).toInstant();
        } catch (DateTimeException ignored) {
            // clock bytes not set
        }
        if (lat == null || lon == null) {
            lat = null;
            lon = null;
        }
        return lat == null && time == null ? Optional.empty()
                : Optional.of(new TelemetrySample(offset, time, lat, lon, null, null, null));
    }

    private static Double coordinate(double v, double max) {
        if (Double.isNaN(v) || v == NO_FIX || v == 0) {
            return null;
        }
        if (Math.abs(v) > max && Math.abs(v) <= max * 100) {
            double sign = Math.signum(v);
            double a = Math.abs(v);
            double deg = Math.floor(a / 100);
            v = sign * (deg + (a - deg * 100) / 60); // NMEA ddmm.mmmm
        }
        return Math.abs(v) <= max ? v : null;
    }
}
