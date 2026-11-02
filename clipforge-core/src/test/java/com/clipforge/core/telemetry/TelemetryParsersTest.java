package com.clipforge.core.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryParsersTest {

    @TempDir
    Path tmp;

    @Test
    void sigmaStarPacketFromTheRealCameraHasTimeButNoFix() {
        // packet captured from NO20261101-233933-004116F.mp4 (camera without GPS module)
        byte[] d = HexFormat.of().parseHex("0000e0ffffffef410000e0ffffffef41ffffffffffff7e0a01172722ff00ffff");
        TelemetrySample s = new SigmaStarParser(ZoneOffset.UTC).decode(d, Duration.ZERO).orElseThrow();
        assertFalse(s.hasPosition());
        assertEquals(LocalDateTime.of(2026, 11, 1, 23, 39, 34).toInstant(ZoneOffset.UTC), s.time());
    }

    @Test
    void sigmaStarWithFixInDegreesAndNmea() {
        ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        b.putDouble(0, 41.0082).putDouble(8, 28.9784);
        byte[] d = b.array();
        System.arraycopy(new byte[]{0x7e, 0x0a, 0x01, 0x17, 0x27, 0x22}, 0, d, 22, 6);
        SigmaStarParser p = new SigmaStarParser(ZoneOffset.UTC);
        TelemetrySample deg = p.decode(d, Duration.ofSeconds(5)).orElseThrow();
        assertEquals(41.0082, deg.latitude(), 1e-6);
        assertEquals(Duration.ofSeconds(5), deg.offset());

        b.putDouble(0, 4100.492).putDouble(8, 2858.704); // ddmm.mmmm
        TelemetrySample nmea = p.decode(b.array(), Duration.ZERO).orElseThrow();
        assertEquals(41.0082, nmea.latitude(), 1e-4);
        assertEquals(28.9784, nmea.longitude(), 1e-4);
    }

    private static byte[] klv(String key, char type, int size, int repeat, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(key.getBytes(StandardCharsets.US_ASCII));
        o.write(type);
        o.write(size);
        o.write(repeat >> 8);
        o.write(repeat & 0xff);
        o.writeBytes(payload);
        int pad = (4 - payload.length % 4) % 4;
        o.writeBytes(new byte[pad]);
        return o.toByteArray();
    }

    @Test
    void goProGps5WithScaleAndUtcTime() {
        ByteBuffer scal = ByteBuffer.allocate(20).putInt(10_000_000).putInt(10_000_000).putInt(1000).putInt(1000).putInt(100);
        ByteBuffer gps = ByteBuffer.allocate(40);
        gps.putInt(410082000).putInt(289784000).putInt(39000).putInt(10000).putInt(1000); // 10 m/s
        gps.putInt(410083000).putInt(289785000).putInt(39500).putInt(12000).putInt(1200);
        byte[] strm = concat(klv("GPSU", 'U', 16, 1, "261101120000.000".getBytes(StandardCharsets.US_ASCII)),
                klv("SCAL", 'l', 4, 5, scal.array()), klv("GPS5", 'l', 20, 2, gps.array()));
        byte[] payload = klv("DEVC", (char) 0, 1, 0, new byte[0]);
        payload = concat(klvContainer("DEVC", klvContainer("STRM", strm)));
        List<TelemetrySample> s = GoProGpmfParser.parsePacket(payload, 10, 1);
        assertEquals(2, s.size());
        assertEquals(41.0082, s.get(0).latitude(), 1e-6);
        assertEquals(28.9784, s.get(0).longitude(), 1e-6);
        assertEquals(39.0, s.get(0).altitude(), 1e-6);
        assertEquals(36.0, s.get(0).speedKmh(), 1e-6, "m/s to km/h");
        assertEquals(Duration.ofMillis(10_500), s.get(1).offset());
        assertEquals(Instant.parse("2026-11-01T12:00:00Z"), s.get(0).time());
    }

    private static byte[] klvContainer(String key, byte[] content) {
        return concat(klv(key, (char) 0, 4, content.length / 4, new byte[0]), content);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    @Test
    void djiSrtLayouts() {
        String srt = """
                1
                00:00:00,000 --> 00:00:00,033
                <font size="28">FrameCnt: 1, DiffTime: 33ms
                2026-11-01 12:00:00.123
                [iso: 100] [latitude: 41.008200] [longitude: 28.978400] [rel_alt: 1.2 abs_alt: 55.500]</font>

                2
                00:00:01,000 --> 00:00:02,000
                HOME(28.9,41.0) 2026-11-01 12:00:01
                GPS(28.978500,41.008300,56) ISO:100
                """;
        List<TelemetrySample> s = new DjiSrtParser(ZoneOffset.UTC).parse(srt);
        assertEquals(2, s.size());
        assertEquals(41.0082, s.get(0).latitude(), 1e-6);
        assertEquals(55.5, s.get(0).altitude(), 1e-6);
        assertEquals(Instant.parse("2026-11-01T12:00:00.123Z"), s.get(0).time());
        assertEquals(41.0083, s.get(1).latitude(), 1e-6);
        assertEquals(28.9785, s.get(1).longitude(), 1e-6);
        assertEquals(Duration.ofSeconds(1), s.get(1).offset());
    }

    @Test
    void goProGps9CarriesItsOwnTime() {
        ByteBuffer scal = ByteBuffer.allocate(36).putInt(10_000_000).putInt(10_000_000).putInt(1000).putInt(1000)
                .putInt(100).putInt(1).putInt(1000).putInt(100).putInt(1);
        ByteBuffer gps = ByteBuffer.allocate(32);
        gps.putInt(410082000).putInt(289784000).putInt(39000).putInt(5000).putInt(500)
                .putInt(9801).putInt(43_200_000).putShort((short) 120).putShort((short) 3); // 2026-11-01 12:00, 3D fix
        byte[] strm = concat(klv("SCAL", 'l', 4, 9, scal.array()), klv("GPS9", '?', 32, 1, gps.array()));
        List<TelemetrySample> s = GoProGpmfParser.parsePacket(klvContainer("DEVC", klvContainer("STRM", strm)), 0, 1);
        assertEquals(1, s.size());
        assertEquals(41.0082, s.getFirst().latitude(), 1e-6);
        assertEquals(18.0, s.getFirst().speedKmh(), 1e-6);
        assertEquals(Instant.parse("2026-11-01T12:00:00Z"), s.getFirst().time());
    }

    @Test
    void djiLongtitudeSpelling() {
        String srt = """
                1
                00:00:00,000 --> 00:00:01,000
                [iso : 100] [latitude : 41.0082] [longtitude : 28.9784] [altitude: 120.5]
                """;
        TelemetrySample s = new DjiSrtParser(ZoneOffset.UTC).parse(srt).getFirst();
        assertTrue(s.hasPosition());
        assertEquals(28.9784, s.longitude(), 1e-6);
        assertEquals(120.5, s.altitude(), 1e-6);
    }

    @Test
    void iso6709DegreesMinutesSeconds() {
        TelemetrySample dm = LocationTagParser.parseIso6709("+4100.492+02858.704/").orElseThrow();
        assertEquals(41.0082, dm.latitude(), 1e-4);
        assertEquals(28.9784, dm.longitude(), 1e-4);
        TelemetrySample dms = LocationTagParser.parseIso6709("-410029.5-0285842.2/").orElseThrow();
        assertEquals(-41.00819, dms.latitude(), 1e-4);
        assertEquals(-28.97839, dms.longitude(), 1e-4);
    }

    @Test
    void iso6709LocationTag() {
        TelemetrySample s = LocationTagParser.parseIso6709("+41.0082+028.9784+039.000/").orElseThrow();
        assertEquals(41.0082, s.latitude(), 1e-6);
        assertEquals(28.9784, s.longitude(), 1e-6);
        assertEquals(39.0, s.altitude(), 1e-6);
        assertTrue(LocationTagParser.parseIso6709("+00.0000+000.0000/").isEmpty(), "0,0 is not a real fix");
    }

    @Test
    void gpxAndCsvExport() throws Exception {
        Telemetry t = new Telemetry("test", List.of(
                new TelemetrySample(Duration.ZERO, null, 41.0, 29.0, 10.0, 50.0, null),
                new TelemetrySample(Duration.ofSeconds(1), null, null, null, null, null, null),
                new TelemetrySample(Duration.ofSeconds(2), Instant.parse("2026-11-01T12:00:02Z"), 41.001, 29.001,
                        null, null, null)));
        Path gpx = tmp.resolve("track.gpx");
        int n = TelemetryExport.gpx(t, "Trip <1>", Instant.parse("2026-11-01T12:00:00Z"), gpx);
        assertEquals(2, n);
        String xml = Files.readString(gpx);
        assertTrue(xml.contains("<name>Trip &lt;1&gt;</name>"));
        assertTrue(xml.contains("<trkpt lat=\"41.0000000\" lon=\"29.0000000\"><ele>10.0</ele><time>2026-11-01T12:00:00Z</time>"));
        Path csv = tmp.resolve("track.csv");
        assertEquals(3, TelemetryExport.csv(t, csv));
        List<String> lines = Files.readAllLines(csv);
        assertEquals(4, lines.size());
        assertEquals("1.000,,,,,,", lines.get(2));
    }

    @Test
    void joinShiftsLaterClips() {
        Telemetry a = new Telemetry("x", List.of(new TelemetrySample(Duration.ofSeconds(1), null, 1.0, 1.0, null, null, null)));
        Telemetry b = new Telemetry("x", List.of(new TelemetrySample(Duration.ofSeconds(1), null, 2.0, 2.0, null, null, null)));
        Telemetry j = Telemetry.join(List.of(a, b), List.of(Duration.ofSeconds(120), Duration.ofSeconds(120)));
        assertEquals(Duration.ofSeconds(121), j.samples().get(1).offset());
        assertNull(Telemetry.NONE.samples().isEmpty() ? null : "x");
    }
}
