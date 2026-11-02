package com.clipforge.core.ffmpeg;

import com.clipforge.core.model.MediaInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Reads {@link MediaInfo} with {@code ffprobe -print_format json}. */
public final class ProbeService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 30;

    private final FfmpegTools tools;

    public ProbeService(FfmpegTools tools) {
        this.tools = tools;
    }

    /** @return media info, or empty if ffprobe cannot read the file (missing moov, not a media file, ...) */
    public Optional<MediaInfo> probe(Path file) {
        try {
            Process p = new ProcessBuilder(tools.ffprobe().toString(),
                    "-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.toString())
                    .start();
            p.getOutputStream().close();
            // drain stderr concurrently so a chatty error output can never block the process
            CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> readQuietly(p));
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return Optional.empty();
            }
            err.join();
            if (p.exitValue() != 0) {
                return Optional.empty();
            }
            return parse(new String(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static byte[] readQuietly(Process p) {
        try {
            return p.getErrorStream().readAllBytes();
        } catch (IOException e) {
            return new byte[0];
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
                    }
                }
                case "audio" -> {
                    if (audioCodec == null) {
                        audioCodec = s.path("codec_name").asText(null);
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
                videoCodec, width, height, fps, audioCodec, data, created));
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
