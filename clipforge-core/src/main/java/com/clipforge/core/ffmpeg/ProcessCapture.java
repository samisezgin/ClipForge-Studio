package com.clipforge.core.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs a short-lived command and captures stdout, with a real timeout (the process is killed when it expires). */
final class ProcessCapture {

    /** @param exitCode -1 if the process timed out */
    record Output(int exitCode, String stdout) {
    }

    private ProcessCapture() {
    }

    static Output run(List<String> command, Duration timeout) throws IOException {
        Process p = new ProcessBuilder(command).start();
        p.getOutputStream().close();
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> readAll(p.getInputStream()));
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> readAll(p.getErrorStream()));
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Output(-1, "");
            }
            err.join();
            return new Output(p.exitValue(), new String(out.join(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
