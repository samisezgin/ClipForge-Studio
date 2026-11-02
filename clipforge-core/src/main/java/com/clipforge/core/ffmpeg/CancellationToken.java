package com.clipforge.core.ffmpeg;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Cancels a running FFmpeg process: sends {@code q} on stdin for a clean shutdown and kills the process if it does
 * not exit within the grace period.
 */
public final class CancellationToken {

    private static final long GRACE_MILLIS = 2000;

    private volatile boolean cancelled;
    /** Set when a running process was actually told to stop (FFmpeg then exits with 0 but its output is cut). */
    private volatile boolean interrupted;
    private volatile Process process;

    public boolean isCancelled() {
        return cancelled;
    }

    /** {@code true} if a live process was stopped through this token, whatever its exit code. */
    public boolean interruptedProcess() {
        return interrupted;
    }

    public void cancel() {
        cancelled = true;
        Process p = process;
        if (p != null) {
            stop(p);
        }
    }

    void attach(Process p) {
        process = p;
        if (cancelled) {
            stop(p);
        }
    }

    void detach() {
        process = null;
    }

    private void stop(Process p) {
        if (p.isAlive()) {
            interrupted = true;
        }
        Thread.ofVirtual().start(() -> {
            try {
                OutputStream in = p.getOutputStream();
                in.write("q\n".getBytes(StandardCharsets.US_ASCII));
                in.flush();
            } catch (IOException ignored) {
                // process already gone or stdin closed
            }
            try {
                if (!p.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        });
    }
}
