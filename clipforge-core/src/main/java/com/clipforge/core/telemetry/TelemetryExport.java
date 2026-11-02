package com.clipforge.core.telemetry;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Writes telemetry as GPX 1.1 (positions only) or CSV (every sample). */
public final class TelemetryExport {

    private TelemetryExport() {
    }

    /**
     * @param fallbackStart used for samples without an absolute time (e.g. start of the recording), may be null
     * @return number of track points written
     */
    public static int gpx(Telemetry t, String name, Instant fallbackStart, Path out) throws IOException {
        int n = 0;
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write("<gpx version=\"1.1\" creator=\"ClipForge Studio\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
            w.write("  <trk>\n    <name>" + xml(name) + "</name>\n    <trkseg>\n");
            for (TelemetrySample s : t.samples()) {
                if (!s.hasPosition()) {
                    continue;
                }
                w.write(String.format(Locale.ROOT, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">", s.latitude(), s.longitude()));
                if (s.altitude() != null) {
                    w.write(String.format(Locale.ROOT, "<ele>%.1f</ele>", s.altitude()));
                }
                Instant time = s.time() != null ? s.time() : fallbackStart == null ? null : fallbackStart.plus(s.offset());
                if (time != null) {
                    w.write("<time>" + DateTimeFormatter.ISO_INSTANT.format(time) + "</time>");
                }
                w.write("</trkpt>\n");
                n++;
            }
            w.write("    </trkseg>\n  </trk>\n</gpx>\n");
        }
        return n;
    }

    public static int csv(Telemetry t, Path out) throws IOException {
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("offset_s,time_utc,latitude,longitude,altitude_m,speed_kmh,heading_deg\n");
            for (TelemetrySample s : t.samples()) {
                w.write(String.format(Locale.ROOT, "%.3f,%s,%s,%s,%s,%s,%s%n", s.offset().toMillis() / 1000.0,
                        s.time() == null ? "" : DateTimeFormatter.ISO_INSTANT.format(s.time()),
                        num(s.hasPosition() ? s.latitude() : null, 7), num(s.hasPosition() ? s.longitude() : null, 7),
                        num(s.altitude(), 1), num(s.speedKmh(), 1), num(s.heading(), 1)));
            }
        }
        return t.samples().size();
    }

    private static String num(Double v, int decimals) {
        return v == null ? "" : String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
