package com.clipforge.core.telemetry;

import com.clipforge.core.ffmpeg.FfmpegTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Streams and container tags of a file, plus helpers to read raw data packets with ffprobe. */
public record StreamTable(List<Stream> streams, Map<String, String> formatTags) {

    /** One stream: index, type ({@code video/audio/data}), codec tag (e.g. {@code gpmd}) and handler name. */
    public record Stream(int index, String type, String codecTag, String handler, int packets) {
    }

    /** One data packet: presentation time in seconds and payload. */
    public record Packet(double time, byte[] data) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    public static StreamTable read(Path file, FfmpegTools tools) throws IOException {
        // no -count_packets: it would demux a multi-GB file just to count
        JsonNode root = JSON.readTree(run(tools, List.of("-v", "error", "-print_format", "json", "-show_format",
                "-show_streams", file.toString())));
        List<Stream> list = new ArrayList<>();
        for (JsonNode s : root.path("streams")) {
            list.add(new Stream(s.path("index").asInt(), s.path("codec_type").asText(),
                    s.path("codec_tag_string").asText(""), s.path("tags").path("handler_name").asText(""),
                    s.path("nb_read_packets").asInt(0)));
        }
        Map<String, String> tags = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        root.path("format").path("tags").properties().forEach(e -> tags.put(e.getKey(), e.getValue().asText()));
        return new StreamTable(List.copyOf(list), tags);
    }

    /** All packets of one stream with their payload bytes. */
    public static List<Packet> packets(Path file, int streamIndex, FfmpegTools tools) throws IOException {
        JsonNode root = JSON.readTree(run(tools, List.of("-v", "error", "-print_format", "json",
                "-select_streams", String.valueOf(streamIndex), "-show_entries", "packet=pts_time,data",
                "-show_data", file.toString())));
        List<Packet> list = new ArrayList<>();
        for (JsonNode p : root.path("packets")) {
            list.add(new Packet(p.path("pts_time").asDouble(0), hexDump(p.path("data").asText(""))));
        }
        return list;
    }

    /** Parses ffprobe's hex dump ({@code 00000000: 0000 e0ff ...  ascii}). */
    static byte[] hexDump(String dump) {
        StringBuilder hex = new StringBuilder();
        for (String line : dump.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String body = line.substring(colon + 1);
            // the hex part is 8 groups of 4 hex digits; the ASCII column starts after two spaces
            int ascii = body.indexOf("  ", 1);
            String hexPart = ascii > 0 ? body.substring(0, ascii) : body;
            hex.append(hexPart.replace(" ", ""));
        }
        return HexFormat.of().parseHex(hex.toString());
    }

    private static String run(FfmpegTools tools, List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(tools.ffprobe().toString());
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        p.getOutputStream().close();
        // read concurrently so the timeout also applies to an ffprobe that hangs without closing stdout
        java.util.concurrent.CompletableFuture<byte[]> reader = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return p.getInputStream().readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });
        byte[] out;
        try {
            if (!p.waitFor(300, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("ffprobe timed out");
            }
            out = reader.join();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("ffprobe failed for " + cmd.getLast());
        }
        return new String(out, StandardCharsets.UTF_8);
    }
}
