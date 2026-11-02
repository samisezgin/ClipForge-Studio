package com.clipforge.desktop;

import atlantafx.base.theme.PrimerDark;
import com.clipforge.core.ffmpeg.FfmpegLocator;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.project.JobState;
import com.clipforge.desktop.view.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Starts the real UI against synthetic clips: add folder → scan → merge → job done. Writes snapshots of the window
 * (English and Turkish) to {@code test-output/} for visual review. Skipped without FFmpeg or a display.
 */
class DesktopSmokeTest {

    private static final Path OUT = Path.of(System.getProperty("user.dir")).getParent().resolve("test-output");
    private static Preferences prefs;

    @TempDir
    static Path tmp;

    @BeforeAll
    static void startFx() throws Exception {
        assumeTrue(!Boolean.getBoolean("java.awt.headless"), "no display");
        System.setProperty("clipforge.home", tmp.resolve("home").toString());
        prefs = Preferences.userRoot().node("com/clipforge/smoke-test");
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyRunning) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (prefs != null) {
            prefs.removeNode();
        }
    }

    @Test
    void addScanMergeAndSnapshot() throws Exception {
        Optional<FfmpegTools> tools = FfmpegLocator.locate(null);
        assumeTrue(tools.isPresent(), "FFmpeg not installed");
        Path folder = Files.createDirectories(tmp.resolve("1 kasım araç kayıt"));
        makeClip(tools.get(), folder.resolve("NO20261101-100000-000001F.mp4"));
        makeClip(tools.get(), folder.resolve("NO20261101-100003-000002F.mp4"));
        makeClip(tools.get(), folder.resolve("NO20261101-120000-000003F.mp4"));
        Path outDir = Files.createDirectories(tmp.resolve("out"));
        Files.createDirectories(OUT);

        AppSettings settings = new AppSettings(prefs);
        Messages.use("en", List.of());
        AppController controller = onFx(() -> {
            Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
            AppController c = new AppController(settings);
            assertTrue(c.initFfmpeg());
            c.newProject();
            return c;
        });
        Stage stage = onFx(() -> show(controller, "en"));

        onFx(() -> {
            controller.addFolder(folder);
            return null;
        });
        waitUntil(() -> onFxQuiet(() -> !controller.scanningProperty().get() && controller.sessions().size() == 2));
        assertEquals(3, onFx(() -> controller.selectedClips().size()), "new clips are selected by default");

        onFx(() -> {
            controller.setOutputDir(outDir);
            AppController.MergePlan plan = controller.planMerge();
            assertTrue(plan.ok(), String.valueOf(plan.errorKey()));
            controller.startMerge(plan.specs());
            return null;
        });
        waitUntil(() -> onFxQuiet(() -> controller.jobs().size() == 2
                && controller.jobs().stream().allMatch(j -> j.getState() == JobState.DONE)));
        try (var files = Files.list(outDir)) {
            assertEquals(2, files.count());
        }
        Thread.sleep(300);
        snapshot(stage, OUT.resolve("ui-en.png"));

        Messages.use("tr", List.of());
        Stage trStage = onFx(() -> {
            controller.retranslate();
            stage.close();
            return show(controller, "tr");
        });
        Thread.sleep(300);
        snapshot(trStage, OUT.resolve("ui-tr.png"));
        onFx(() -> {
            trStage.close();
            return null;
        });
        Messages.use("en", List.of());
    }

    private static Stage show(AppController controller, String lang) {
        Stage s = new Stage();
        MainView view = new MainView(controller, s, null, t -> { }, d -> { });
        Scene scene = new Scene(view.root(), 1280, 820);
        scene.getStylesheets().add(ClipForgeApp.class.getResource("app.css").toExternalForm());
        s.setScene(scene);
        s.show();
        return s;
    }

    private static void snapshot(Stage stage, Path file) throws Exception {
        onFx(() -> {
            WritableImage img = stage.getScene().snapshot(null);
            Files.write(file, png(img));
            return null;
        });
    }

    /** Minimal PNG encoder (RGB, no filtering) so the test does not need javafx.swing / java.desktop. */
    private static byte[] png(WritableImage img) throws java.io.IOException {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        var reader = img.getPixelReader();
        var raw = new java.io.ByteArrayOutputStream();
        for (int y = 0; y < h; y++) {
            raw.write(0);
            for (int x = 0; x < w; x++) {
                int argb = reader.getArgb(x, y);
                raw.write(argb >> 16 & 0xff);
                raw.write(argb >> 8 & 0xff);
                raw.write(argb & 0xff);
            }
        }
        var out = new java.io.ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        var ihdr = java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0).array();
        chunk(out, "IHDR", ihdr);
        var z = new java.io.ByteArrayOutputStream();
        try (var d = new java.util.zip.DeflaterOutputStream(z)) {
            raw.writeTo(d);
        }
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(java.io.ByteArrayOutputStream out, String type, byte[] data) throws java.io.IOException {
        out.write(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        var crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data);
        out.write(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static void makeClip(FfmpegTools t, Path file) throws Exception {
        Process p = new ProcessBuilder(t.ffmpeg().toString(), "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30:duration=3",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=3",
                "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", file.toString()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, out);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3600)));
    }

    // ---------------------------------------------------------------- FX thread helpers

    interface FxCall<T> {
        T call() throws Exception;
    }

    private static <T> T onFx(FxCall<T> call) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(call.call());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(30, TimeUnit.SECONDS);
    }

    private static boolean onFxQuiet(Supplier<Boolean> s) {
        try {
            return onFx(s::get);
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitUntil(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!condition.get()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(100);
        }
    }
}
