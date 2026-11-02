package com.clipforge.desktop;

import atlantafx.base.theme.PrimerDark;
import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicSettings;
import com.clipforge.core.compress.QualityBand;
import com.clipforge.core.ffmpeg.FfmpegLocator;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.project.JobState;
import com.clipforge.desktop.view.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

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

        // compression through the controller, the same path the Compress button uses
        waitUntil(() -> onFxQuiet(() -> controller.encodersProperty().get() != null));
        boolean canCompress = onFx(() -> controller.compressOptions() != null);
        Stage[] stageRef = {stage};
        if (canCompress) {
            Path tune = tmp.resolve("müzik.mp3");
            Process mp = new ProcessBuilder(tools.get().ffmpeg().toString(), "-v", "error", "-y", "-f", "lavfi",
                    "-i", "sine=frequency=600:duration=2", "-c:a", "libmp3lame", tune.toString())
                    .redirectErrorStream(true).start();
            mp.getOutputStream().close();
            mp.getInputStream().readAllBytes();
            assertEquals(0, mp.waitFor());
            onFx(() -> {
                controller.setAudio(AudioMode.MUSIC,
                        MusicSettings.DEFAULT.withTracks(List.of(tune.toString())));
                // the audio panel reads the project on load; rebuild the view to show the music settings
                return null;
            });
            onFx(() -> {
                controller.setCompressSource(false, null);
                AppController.CompressPlan plan = controller.planCompress();
                assertTrue(plan.ok(), String.valueOf(plan.errorKey()));
                assertEquals(2, plan.specs().size(), "one job per session");
                controller.startCompress(plan.specs());
                return null;
            });
            waitUntil(() -> onFxQuiet(() -> controller.jobs().size() == 4
                    && controller.jobs().stream().allMatch(j -> j.getState() == JobState.DONE)));
            try (var files = Files.list(outDir)) {
                assertEquals(4, files.filter(f -> f.toString().endsWith(".mp4")).count(), "no part/work files left");
            }
            try (var files = Files.list(outDir)) {
                for (Path f : files.filter(x -> x.getFileName().toString().contains("_av1")).toList()) {
                    var m = new ProbeService(tools.get()).probe(f).orElseThrow();
                    assertEquals("aac", m.audioCodec(), "background music in " + f.getFileName());
                }
            }
            onFx(() -> {
                // speed-up alone: every clip on its own, re-encoded close to the source
                controller.setSteps(false, true, false);
                controller.setSpeedFactor(3);
                assertEquals(AppController.RunKind.ENCODE, controller.runKind());
                AppController.CompressPlan speedOnly = controller.planCompress();
                assertTrue(speedOnly.ok(), String.valueOf(speedOnly.errorKey()));
                assertEquals(3, speedOnly.specs().size(), "one output per clip without merging");
                for (var s : speedOnly.specs()) {
                    var o = s.options();
                    assertEquals(3, o.speed());
                    assertEquals(o.profile().range(QualityBand.TRANSPARENT)[1], o.quality());
                    assertTrue(s.output().endsWith("_3x.mp4"), s.output());
                }
                // all steps: joined per session, sped up and compressed with the compress tab's quality
                controller.setSteps(true, true, true);
                AppController.CompressPlan all = controller.planCompress();
                assertEquals(2, all.specs().size(), "one output per session");
                assertEquals(controller.compressOptions().quality(), all.specs().getFirst().options().quality());
                assertEquals(3, all.specs().getFirst().options().speed());
                controller.setSteps(true, false, false);
                assertEquals(AppController.RunKind.MERGE, controller.runKind());
                return null;
            });
            Stage musicStage = onFx(() -> {
                stage.close();
                return show(controller, "en");
            });
            stageRef[0] = musicStage;
            snapshot(stageRef[0], OUT.resolve("ui-steps-en.png")); // steps panel at the top of the side panel
            onFx(() -> {
                // dragging the divider far right must not make the side panel narrower than its content
                var split = (SplitPane) stageRef[0].getScene().lookup(".split-pane");
                split.setDividerPositions(0.99);
                return null;
            });
            Thread.sleep(300);
            onFx(() -> {
                var side = sideScroll(stageRef[0]);
                var content = (Region) side.getContent();
                assertTrue(side.getMinWidth() > 320, "min width from content: " + side.getMinWidth());
                assertTrue(side.getWidth() >= side.getMinWidth() - 1, side.getWidth() + " < " + side.getMinWidth());
                assertTrue(content.getWidth() >= content.minWidth(-1) - 1, "content fits without sideways scrolling");
                return null;
            });
            onFx(() -> {
                var tabs = (TabPane) stageRef[0].getScene().lookup(".tab-pane");
                tabs.getSelectionModel().select(1); // speed up
                sideScroll(stageRef[0]).setVvalue(1);
                return null;
            });
            Thread.sleep(300);
            snapshot(stageRef[0], OUT.resolve("ui-speed-en.png"));
            onFx(() -> {
                var tabs = (TabPane) stageRef[0].getScene().lookup(".tab-pane");
                tabs.getSelectionModel().select(2); // compress
                sideScroll(stageRef[0]).setVvalue(1);
                return null;
            });
            Thread.sleep(300);
            snapshot(stageRef[0], OUT.resolve("ui-compress-en.png"));
            onFx(() -> {
                // scrolled to the bottom, the last line of the compress tab must be inside the visible area
                var side = sideScroll(stageRef[0]);
                var tabs = (TabPane) stageRef[0].getScene().lookup(".tab-pane");
                var panel = (Region) tabs.getSelectionModel().getSelectedItem().getContent();
                var last = panel.getChildrenUnmodifiable().getLast();
                double lastBottom = last.localToScene(last.getBoundsInLocal()).getMaxY();
                double viewBottom = side.localToScene(side.getLayoutBounds()).getMaxY();
                var content = (Region) side.getContent();
                assertTrue(lastBottom <= viewBottom + 1, "last line cut off: " + lastBottom + " > " + viewBottom
                        + " panel h=" + panel.getHeight() + " pref(w)=" + panel.prefHeight(panel.getWidth())
                        + " pref(-1)=" + panel.prefHeight(-1) + " tabs h=" + tabs.getHeight() + " tabs pref="
                        + tabs.prefHeight(-1) + " content h=" + content.getHeight() + " content pref(w)="
                        + content.prefHeight(content.getWidth()) + " vvalue=" + side.getVvalue());
                return null;
            });
            onFx(() -> {
                stageRef[0].close();
                return null;
            });
        }

        Messages.use("tr", List.of());
        Stage trStage = onFx(() -> {
            controller.retranslate();
            stageRef[0].close();
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

    private static ScrollPane sideScroll(Stage stage) {
        return stage.getScene().getRoot().lookupAll(".scroll-pane").stream()
                .filter(n -> n.getStyleClass().contains("side-panel"))
                .map(n -> (ScrollPane) n).findFirst().orElseThrow();
    }

    private static void snapshot(Stage stage, Path file) throws Exception {
        onFx(() -> {
            WritableImage img = stage.getScene().snapshot(null);
            Files.write(file, png(img));
            return null;
        });
    }

    /** Minimal PNG encoder (RGB, no filtering) so the test does not need javafx.swing / java.desktop. */
    private static byte[] png(WritableImage img) throws IOException {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        var reader = img.getPixelReader();
        var raw = new ByteArrayOutputStream();
        for (int y = 0; y < h; y++) {
            raw.write(0);
            for (int x = 0; x < w; x++) {
                int argb = reader.getArgb(x, y);
                raw.write(argb >> 16 & 0xff);
                raw.write(argb >> 8 & 0xff);
                raw.write(argb & 0xff);
            }
        }
        var out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        var ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0).array();
        chunk(out, "IHDR", ihdr);
        var z = new ByteArrayOutputStream();
        try (var d = new DeflaterOutputStream(z)) {
            raw.writeTo(d);
        }
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        var crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
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
