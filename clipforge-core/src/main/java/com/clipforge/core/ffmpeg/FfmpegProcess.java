package com.clipforge.core.ffmpeg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Runs an FFmpeg command line, parsing {@code -progress pipe:1} output from stdout. The caller's command must not
 * contain {@code -progress}; it is added here.
 */
public final class FfmpegProcess {

    private static final Logger LOG = Logger.getLogger(FfmpegProcess.class.getName());
    private static final int STDERR_TAIL_LINES = 60;

    private FfmpegProcess() {
    }

    /**
     * @param command  full command, first element is the ffmpeg executable
     * @param total    expected output duration (for percentage), {@link Duration#ZERO} if unknown
     * @param progress receives progress snapshots, may be {@code null}
     * @param token    cancellation token, may be {@code null}
     */
    public static FfmpegResult run(List<String> command, Duration total, Consumer<Progress> progress,
                                   CancellationToken token) throws IOException {
        List<String> cmd = new ArrayList<>(command.size() + 4);
        cmd.add(command.getFirst());
        cmd.addAll(List.of("-hide_banner", "-nostats", "-progress", "pipe:1"));
        cmd.addAll(command.subList(1, command.size()));
        LOG.fine(() -> "Running: " + String.join(" ", cmd));

        Process p = new ProcessBuilder(cmd).start();
        if (token != null) {
            token.attach(p);
        }
        Deque<String> tail = new ArrayDeque<>();
        Thread errReader = Thread.ofVirtual().start(() -> readStderr(p, tail));
        try {
            readProgress(p, total, progress);
            int exit = p.waitFor();
            errReader.join();
            List<String> lines;
            synchronized (tail) {
                lines = List.copyOf(tail);
            }
            return new FfmpegResult(exit, token != null && token.isCancelled(), lines);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for ffmpeg", e);
        } finally {
            if (token != null) {
                token.detach();
            }
        }
    }

    private static void readProgress(Process p, Duration total, Consumer<Progress> listener) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            long outTimeUs = 0;
            double speed = 0;
            long size = -1;
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "out_time_us", "out_time_ms" -> outTimeUs = parseLong(value, outTimeUs);
                    case "speed" -> speed = parseSpeed(value, speed);
                    case "total_size" -> size = parseLong(value, size);
                    case "progress" -> {
                        if (listener != null) {
                            // out_time_ms is (historically) also in microseconds
                            listener.accept(new Progress(Duration.ofNanos(Math.max(0, outTimeUs) * 1000), total,
                                    speed, size));
                        }
                    }
                    default -> {
                        // frame, fps, bitrate, ... not needed
                    }
                }
            }
        }
    }

    private static void readStderr(Process p, Deque<String> tail) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (tail) {
                    tail.addLast(line);
                    if (tail.size() > STDERR_TAIL_LINES) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private static long parseLong(String v, long fallback) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback; // "N/A"
        }
    }

    private static double parseSpeed(String v, double fallback) {
        String s = v.endsWith("x") ? v.substring(0, v.length() - 1) : v;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
