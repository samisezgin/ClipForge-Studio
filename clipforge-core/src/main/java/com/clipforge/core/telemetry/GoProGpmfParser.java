package com.clipforge.core.telemetry;

import com.clipforge.core.ffmpeg.FfmpegTools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GoPro GPMF ({@code gpmd} data track). Reads the GPS streams: {@code GPS5} (lat, lon, alt, 2D speed, 3D speed) or
 * {@code GPS9} (newer cameras), scaled by {@code SCAL}, with {@code GPSU} as UTC time. Other sensors (ACCL, GYRO)
 * are ignored for now.
 */
public final class GoProGpmfParser implements TelemetryParser {

    private static final DateTimeFormatter GPSU = DateTimeFormatter.ofPattern("yyMMddHHmmss.SSS");

    @Override
    public String id() {
        return "gopro";
    }

    @Override
    public Optional<Telemetry> parse(Path file, StreamTable streams, FfmpegTools tools) throws IOException {
        Optional<StreamTable.Stream> gpmd = streams.streams().stream()
                .filter(s -> s.codecTag().equals("gpmd")).findFirst();
        if (gpmd.isEmpty()) {
            return Optional.empty();
        }
        List<StreamTable.Packet> packets = StreamTable.packets(file, gpmd.get().index(), tools);
        List<TelemetrySample> samples = new ArrayList<>();
        for (int i = 0; i < packets.size(); i++) {
            double start = packets.get(i).time();
            double length = i + 1 < packets.size() ? packets.get(i + 1).time() - start : 1.0;
            samples.addAll(parsePacket(packets.get(i).data(), start, length));
        }
        return samples.isEmpty() ? Optional.empty() : Optional.of(new Telemetry(id(), samples));
    }

    /** Parses one GPMF payload; GPS samples are spread evenly over the packet's duration. */
    static List<TelemetrySample> parsePacket(byte[] data, double start, double length) {
        List<TelemetrySample> out = new ArrayList<>();
        walk(ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN), 0, data.length, new State(), out, start, length);
        return out;
    }

    /** Values that apply to the following GPS5/GPS9 entry within the same stream. */
    private static final class State {
        double[] scale = {1};
        Instant time;
    }

    private static void walk(ByteBuffer b, int from, int to, State st, List<TelemetrySample> out, double start,
                             double length) {
        int pos = from;
        while (pos + 8 <= to) {
            String key = new String(new byte[]{b.get(pos), b.get(pos + 1), b.get(pos + 2), b.get(pos + 3)},
                    StandardCharsets.US_ASCII);
            char type = (char) (b.get(pos + 4) & 0xff);
            int size = b.get(pos + 5) & 0xff;
            int repeat = b.getShort(pos + 6) & 0xffff;
            int len = size * repeat;
            int data = pos + 8;
            if (data + len > to) {
                return; // truncated
            }
            if (type == 0) {
                // nested container (DEVC, STRM): start a fresh state for each stream
                walk(b, data, data + len, key.equals("STRM") ? new State() : st, out, start, length);
            } else if (key.equals("SCAL")) {
                st.scale = numbers(b, data, type, size, repeat);
            } else if (key.equals("GPSU") && type == 'U') {
                String s = new String(bytes(b, data, Math.min(16, len)), StandardCharsets.US_ASCII).trim();
                try {
                    st.time = LocalDateTime.parse(s, GPSU).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException ignored) {
                    st.time = null;
                }
            } else if ((key.equals("GPS5") || key.equals("GPS9")) && type == 'l') {
                int values = size / 4;
                for (int r = 0; r < repeat; r++) {
                    double[] v = new double[values];
                    for (int k = 0; k < values; k++) {
                        double scale = st.scale.length > k ? st.scale[k] : st.scale[st.scale.length - 1];
                        v[k] = b.getInt(data + r * size + k * 4) / (scale == 0 ? 1 : scale);
                    }
                    double t = start + length * r / Math.max(1, repeat);
                    Instant time = st.time == null ? null
                            : st.time.plusMillis(Math.round(length * 1000 * r / Math.max(1, repeat)));
                    out.add(new TelemetrySample(Duration.ofMillis(Math.round(t * 1000)), time, v[0], v[1],
                            values > 2 ? v[2] : null, values > 3 ? v[3] * 3.6 : null, null));
                }
            }
            pos = data + ((len + 3) & ~3); // payloads are padded to 32 bits
        }
    }

    private static double[] numbers(ByteBuffer b, int at, char type, int size, int repeat) {
        int width = switch (type) {
            case 'b', 'B' -> 1;
            case 's', 'S' -> 2;
            case 'l', 'L', 'f' -> 4;
            default -> 0;
        };
        if (width == 0) {
            return new double[]{1};
        }
        int count = size * repeat / width;
        double[] v = new double[count];
        for (int i = 0; i < count; i++) {
            int p = at + i * width;
            v[i] = switch (type) {
                case 'b' -> b.get(p);
                case 'B' -> b.get(p) & 0xff;
                case 's' -> b.getShort(p);
                case 'S' -> b.getShort(p) & 0xffff;
                case 'l' -> b.getInt(p);
                case 'L' -> Integer.toUnsignedLong(b.getInt(p));
                case 'f' -> b.getFloat(p);
                default -> 1;
            };
        }
        return v;
    }

    private static byte[] bytes(ByteBuffer b, int at, int n) {
        byte[] out = new byte[n];
        b.get(at, out);
        return out;
    }
}
