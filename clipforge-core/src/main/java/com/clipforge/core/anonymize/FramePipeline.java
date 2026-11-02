package com.clipforge.core.anonymize;

import com.clipforge.core.ffmpeg.CancellationToken;
import com.clipforge.core.ffmpeg.FfmpegResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.logging.Logger;

/**
 * Hides plates and faces in a stream of frames: one FFmpeg decodes to raw YUV, the frames are searched and blurred
 * here, and a second FFmpeg encodes them.
 * <p>
 * Detection runs on every {@code interval}-th frame. The frames in between are held back until the next detection,
 * then each gets the boxes moved linearly from the previous to the next detection (matched by overlap), so a passing
 * car stays covered; boxes without a partner are kept for the whole gap. Every box is enlarged a little.
 */
public final class FramePipeline {

    private static final Logger LOG = Logger.getLogger(FramePipeline.class.getName());
    /** Extra margin around every box, so a plate's edge never shows between two detections. */
    static final double GROW = 0.25;

    private final Detectors detectors;
    private final int interval;

    public FramePipeline(Detectors detectors, int interval) {
        this.detectors = detectors;
        this.interval = Math.max(1, interval);
    }

    /**
     * @param decoder FFmpeg writing {@code -f rawvideo -pix_fmt yuv420p} frames of {@code width x height} to stdout
     * @param encoder FFmpeg reading such frames from stdin
     * @param frames  called with the number of frames written so far
     */
    public FfmpegResult run(List<String> decoder, List<String> encoder, int width, int height, LongConsumer frames,
                            CancellationToken token) throws IOException {
        if (token != null && token.isCancelled()) {
            return new FfmpegResult(-1, true, List.of());
        }
        LOG.info(() -> "Running: " + String.join(" ", decoder) + " | (hide plates and faces) | "
                + String.join(" ", encoder));
        Process dec = new ProcessBuilder(decoder).start();
        Process enc = new ProcessBuilder(encoder).start();
        dec.getOutputStream().close();
        enc.getInputStream().close();
        Deque<String> decErr = new ArrayDeque<>();
        Deque<String> encErr = new ArrayDeque<>();
        Thread t1 = Thread.ofVirtual().start(() -> drain(dec.getErrorStream(), decErr));
        Thread t2 = Thread.ofVirtual().start(() -> drain(enc.getErrorStream(), encErr));
        boolean[] cancelled = {false};
        Thread watch = Thread.ofVirtual().start(() -> {
            while (dec.isAlive() || enc.isAlive()) {
                if (token != null && token.isCancelled()) {
                    cancelled[0] = true;
                    dec.destroyForcibly();
                    enc.destroyForcibly();
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        IOException failure = null;
        try (InputStream in = dec.getInputStream(); OutputStream out = enc.getOutputStream()) {
            process(in, out, width, height, frames);
        } catch (IOException e) {
            failure = e; // a broken pipe when one side died; the exit codes tell why
        }
        try {
            int decExit = dec.waitFor();
            int encExit = enc.waitFor();
            watch.interrupt();
            t1.join();
            t2.join();
            List<String> tail = new ArrayList<>();
            synchronized (decErr) {
                tail.addAll(decErr);
            }
            synchronized (encErr) {
                tail.addAll(encErr);
            }
            if (cancelled[0]) {
                return new FfmpegResult(-1, true, tail);
            }
            if (failure != null && decExit == 0 && encExit == 0) {
                tail.add(String.valueOf(failure.getMessage()));
                return new FfmpegResult(1, false, tail);
            }
            return new FfmpegResult(decExit != 0 ? decExit : encExit, false, tail);
        } catch (InterruptedException e) {
            dec.destroyForcibly();
            enc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            if (dec.isAlive()) {
                dec.destroyForcibly();
            }
            if (enc.isAlive()) {
                enc.destroyForcibly();
            }
        }
    }

    private void process(InputStream in, OutputStream out, int width, int height, LongConsumer frames)
            throws IOException {
        int size = YuvFrame.size(width, height);
        List<YuvFrame> pending = new ArrayList<>(); // frames after the last detection, waiting for the next one
        List<Box> previous = null;
        long index = 0;
        long written = 0;
        while (true) {
            byte[] buf = in.readNBytes(size);
            if (buf.length < size) {
                break; // end of stream (a partial frame cannot be used)
            }
            YuvFrame f = new YuvFrame(width, height, buf);
            if (index % interval != 0) {
                pending.add(f);
                index++;
                continue;
            }
            List<Box> now = grow(detectors.detect(f));
            if (previous == null) {
                previous = now; // the first frame of the stream
            }
            written += flush(pending, previous, now, out);
            for (Box b : union(previous, now)) {
                f.blur(b);
            }
            out.write(f.data);
            written++;
            frames.accept(written);
            previous = now;
            index++;
        }
        // the tail after the last detection keeps its boxes
        written += flush(pending, previous == null ? List.of() : previous, previous == null ? List.of() : previous, out);
        frames.accept(written);
        out.flush();
    }

    /** Blurs and writes the held-back frames with boxes moving from {@code from} to {@code to}. */
    private static int flush(List<YuvFrame> pending, List<Box> from, List<Box> to, OutputStream out)
            throws IOException {
        int n = pending.size();
        for (int i = 0; i < n; i++) {
            double t = (i + 1.0) / (n + 1);
            YuvFrame f = pending.get(i);
            for (Box b : between(from, to, t)) {
                f.blur(b);
            }
            out.write(f.data);
        }
        pending.clear();
        return n;
    }

    /**
     * Boxes at fraction {@code t} of the way from one detection to the next: matched boxes (same kind, overlapping)
     * are interpolated; the others are kept as they are, so nothing is uncovered for a moment.
     */
    static List<Box> between(List<Box> from, List<Box> to, double t) {
        List<Box> out = new ArrayList<>();
        boolean[] used = new boolean[to.size()];
        for (Box a : from) {
            int best = -1;
            double bestIou = 0;
            for (int j = 0; j < to.size(); j++) {
                Box b = to.get(j);
                double iou = b.kind() == a.kind() ? a.grow(0.5).iou(b.grow(0.5)) : 0;
                if (!used[j] && iou > bestIou) {
                    best = j;
                    bestIou = iou;
                }
            }
            if (best < 0) {
                out.add(a);
            } else {
                used[best] = true;
                Box b = to.get(best);
                out.add(new Box(a.kind(), a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t,
                        a.w() + (b.w() - a.w()) * t, a.h() + (b.h() - a.h()) * t, Math.max(a.score(), b.score())));
            }
        }
        for (int j = 0; j < to.size(); j++) {
            if (!used[j]) {
                out.add(to.get(j)); // appears in the next detection: cover it already
            }
        }
        return out;
    }

    private static List<Box> union(List<Box> a, List<Box> b) {
        List<Box> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static List<Box> grow(List<Box> boxes) {
        return boxes.stream().map(b -> b.grow(GROW)).toList();
    }

    private static void drain(InputStream err, Deque<String> tail) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (tail) {
                    tail.addLast(line);
                    if (tail.size() > 20) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
    }
}
