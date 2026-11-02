package com.clipforge.core.telemetry;

import com.clipforge.core.ffmpeg.FfmpegTools;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/** Tries every known parser on a file and returns the first telemetry found. */
public final class TelemetryService {

    private static final Logger LOG = Logger.getLogger(TelemetryService.class.getName());

    private final FfmpegTools tools;
    private final List<TelemetryParser> parsers;

    public TelemetryService(FfmpegTools tools, ZoneId zone) {
        this(tools, List.of(new GoProGpmfParser(), new SigmaStarParser(zone), new DjiSrtParser(zone),
                new LocationTagParser()));
    }

    public TelemetryService(FfmpegTools tools, List<TelemetryParser> parsers) {
        this.tools = tools;
        this.parsers = List.copyOf(parsers);
    }

    public Telemetry read(Path file) throws IOException {
        StreamTable streams = StreamTable.read(file, tools);
        for (TelemetryParser p : parsers) {
            try {
                Optional<Telemetry> t = p.parse(file, streams, tools);
                if (t.isPresent() && !t.get().isEmpty()) {
                    return t.get();
                }
            } catch (IOException | RuntimeException e) {
                LOG.warning("Telemetry parser " + p.id() + " failed for " + file.getFileName() + ": " + e);
            }
        }
        return Telemetry.NONE;
    }

    /**
     * Whether the file carries positions or speeds (the camera clock alone does not count). Cheap: reads the stream
     * table and a few samples, never a whole data track.
     */
    public boolean hasData(Path file, Duration duration) {
        try {
            StreamTable streams = StreamTable.read(file, tools);
            for (TelemetryParser p : parsers) {
                try {
                    if (p.hasData(file, streams, tools, duration)) {
                        return true;
                    }
                } catch (IOException | RuntimeException e) {
                    LOG.fine("Telemetry check " + p.id() + " failed for " + file.getFileName() + ": " + e);
                }
            }
        } catch (IOException e) {
            LOG.fine("No stream table for " + file.getFileName() + ": " + e.getMessage());
        }
        return false;
    }

    /** Telemetry of consecutive clips on one timeline. */
    public Telemetry read(List<Path> clips, List<Duration> durations) throws IOException {
        List<Telemetry> parts = new ArrayList<>();
        for (Path c : clips) {
            parts.add(read(c));
        }
        return Telemetry.join(parts, durations);
    }
}
