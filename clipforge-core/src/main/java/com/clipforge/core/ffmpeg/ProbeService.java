package com.clipforge.core.ffmpeg;

import com.clipforge.core.model.MediaInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/** Reads {@link MediaInfo} with {@code ffprobe -print_format json}. */
public final class ProbeService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final FfmpegTools tools;

    public ProbeService(FfmpegTools tools) {
        this.tools = tools;
    }

    /** @return media info, or empty if ffprobe cannot read the file (missing moov, not a media file, ...) */
    public Optional<MediaInfo> probe(Path file) {
        try {
            ProcessCapture.Output out = ProcessCapture.run(List.of(tools.ffprobe().toString(),
                    "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.toString()),
                    TIMEOUT);
            return out.exitCode() != 0 ? Optional.empty() : parse(out.stdout());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Parses ffprobe JSON output. Package-private for tests. */
    static Optional<MediaInfo> parse(String json) throws IOException {
        JsonNode root = JSON.readTree(json);
        JsonNode format = root.path("format");
        double seconds = format.path("duration").asDouble(-1);
        if (seconds <= 0) {
            return Optional.empty();
        }
        String videoCodec = null;
        String audioCodec = null;
        int width = 0;
        int height = 0;
        double fps = 0;
        int data = 0;
        Duration videoStart = Duration.ZERO;
        Duration audioStart = Duration.ZERO;
        int channels = 0;
        for (JsonNode s : root.path("streams")) {
            String type = s.path("codec_type").asText();
            switch (type) {
                case "video" -> {
                    // cover art is an attached picture "video" stream - ignore it
                    if (videoCodec == null && s.path("disposition").path("attached_pic").asInt(0) == 0) {
                        videoCodec = s.path("codec_name").asText(null);
                        width = s.path("width").asInt(0);
                        height = s.path("height").asInt(0);
                        fps = parseRate(s.path("avg_frame_rate").asText("0/0"));
                        videoStart = seconds(s.path("start_time").asText("0"));
                    }
                }
                case "audio" -> {
                    if (audioCodec == null) {
                        audioCodec = s.path("codec_name").asText(null);
                        audioStart = seconds(s.path("start_time").asText("0"));
                        channels = s.path("channels").asInt(0);
                    }
                }
                case "data", "unknown" -> data++;
                default -> {
                    // subtitles, attachments: not relevant
                }
            }
        }
        Instant created = parseInstant(format.path("tags").path("creation_time").asText(null));
        return Optional.of(new MediaInfo(Duration.ofMillis(Math.round(seconds * 1000)),
                videoCodec, width, height, fps, audioCodec, data, created, videoStart, audioStart, channels));
    }

    private static Duration seconds(String s) {
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) && v > 0 ? Duration.ofNanos(Math.round(v * 1e9)) : Duration.ZERO;
        } catch (NumberFormatException e) {
            return Duration.ZERO; // "N/A"
        }
    }

    private static double parseRate(String rate) {
        String[] p = rate.split("/");
        try {
            double num = Double.parseDouble(p[0]);
            double den = p.length > 1 ? Double.parseDouble(p[1]) : 1;
            return den == 0 ? 0 : num / den;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Instant parseInstant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Instant i = Instant.parse(text);
            // some cameras write 1970 or 1904 epoch placeholders
            return i.isBefore(Instant.parse("1990-01-01T00:00:00Z")) ? null : i;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
