package com.clipforge.core.telemetry;

import com.clipforge.core.ffmpeg.FfmpegTools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Extracts telemetry from one kind of source. New camera formats are added by implementing this interface and
 * registering the parser in {@link TelemetryService}.
 */
public interface TelemetryParser {

    /** Short identifier, e.g. {@code gopro}. */
    String id();

    /**
     * @param streams the file's streams as reported by ffprobe
     * @return telemetry, or empty if this parser does not apply to the file
     */
    Optional<Telemetry> parse(Path file, StreamTable streams, FfmpegTools tools) throws IOException;
}
