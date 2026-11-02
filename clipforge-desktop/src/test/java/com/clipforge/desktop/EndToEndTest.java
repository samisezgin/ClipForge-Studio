package com.clipforge.desktop;

import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicSettings;
import com.clipforge.core.compress.CompressOptions;
import com.clipforge.core.compress.CompressRecovery;
import com.clipforge.core.compress.SegmentState;
import com.clipforge.core.ffmpeg.FfmpegLocator;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.merge.MergeMode;
import com.clipforge.core.model.ClipStatus;
import com.clipforge.core.model.MediaInfo;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.JobState;
import com.clipforge.core.project.JobType;
import com.clipforge.core.project.ProjectStore;
import com.clipforge.core.project.RecoveryService;
import com.clipforge.core.telemetry.TelemetryExport;
import com.clipforge.core.telemetry.TelemetryService;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test of the whole workflow through the application controller, on small synthetic clips.
 * Runs only with {@code -Dclipforge.e2e=true}; writes a report to {@code test-output/e2e/report.txt}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndTest {

    private static final Path OUT = Path.of(System.getProperty("user.dir")).getParent().resolve("test-output")
            .resolve("e2e");
    private static final List<String> REPORT = new ArrayList<>();
    private static Path root;
    private static Path source;
    private static Path output;
    private static Path projectFile;
    private static FfmpegTools tools;
    private static Preferences prefs;
    private static AppController controller;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(Boolean.getBoolean("clipforge.e2e"), "e2e disabled (use -Dclipforge.e2e=true)");
        Optional<FfmpegTools> t = FfmpegLocator.locate(null);
        assumeTrue(t.isPresent(), "FFmpeg required");
        tools = t.get();
        deleteTree(OUT);
        root = Files.createDirectories(OUT.resolve("çalışma alanı"));
        source = Files.createDirectories(root.resolve("1 kasım araç kayıt"));
        output = Files.createDirectories(root.resolve("çıktı"));
        projectFile = root.resolve("yolculuk.cfproj");
        System.setProperty("clipforge.home", root.resolve("home").toString());
        prefs = Preferences.userRoot().node("com/clipforge/e2e-test");
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException running) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);

        // session A: three consecutive 6 s clips; session B: one clip 2 h later; a rear-camera clip; a duplicate
        clip("NO20261101-100000-000001F.mp4", 6, source);
        clip("NO20261101-100006-000002F.mp4", 6, source);
        clip("NO20261101-100012-000003F.mp4", 6, source);
        clip("NO20261101-120000-000004F.mp4", 6, source);
        clip("NO20261101-100000-000001R.mp4", 6, source);
        Path backup = Files.createDirectories(root.resolve("yedek"));
        Files.copy(source.resolve("NO20261101-100000-000001F.mp4"), backup.resolve("NO20261101-100000-000001F.mp4"));
        Files.setLastModifiedTime(backup.resolve("NO20261101-100000-000001F.mp4"),
                Files.getLastModifiedTime(source.resolve("NO20261101-100000-000001F.mp4")));
        // a file "still being copied": recent and truncated
        Files.write(source.resolve("NO20261101-120006-000005F.mp4"), new byte[4096]);
        run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=500:duration=4", "-c:a", "libmp3lame", root.resolve("müzik 'test'.mp3").toString()));
        report("setup: 6 clips (2 sessions front, 1 rear, 1 duplicate in a second folder, 1 being copied)");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (controller != null) {
            onFx(() -> {
                controller.shutdown();
                return null;
            });
        }
        if (prefs != null) {
            prefs.removeNode();
        }
        if (!REPORT.isEmpty()) {
            Files.createDirectories(OUT);
            Files.write(OUT.resolve("report.txt"), REPORT, StandardCharsets.UTF_8);
        }
    }

    @Test
    @Order(1)
    void scanFindsSessionsChannelsDuplicatesAndCopyingFiles() throws Exception {
        controller = onFx(() -> {
            AppController c = new AppController(new AppSettings(prefs));
            assertTrue(c.initFfmpeg());
            c.newProject();
            c.saveAs(projectFile);
            c.addFolder(source);
            c.addFolder(root.resolve("yedek"));
            return c;
        });
        waitFor("scan", () -> !controller.scanningProperty().get() && !controller.sessions().isEmpty());
        var clips = onFx(() -> controller.sessions().stream().flatMap(s -> s.clips().stream()).toList());
        long copying = clips.stream().filter(c -> c.status() == ClipStatus.COPYING).count();
        long duplicates = clips.stream().filter(c -> c.status() == ClipStatus.DUPLICATE).count();
        int sessions = onFx(() -> controller.sessions().size());
        assertEquals(1, copying, "the file being copied is not selectable");
        assertEquals(1, duplicates, "the backup copy is recognised");
        assertEquals(3, sessions, "front 10:00, front 12:00, rear 10:00");
        assertEquals(5, onFx(() -> controller.selectedClips().size()), "ready clips are selected by default");
        report("scan: " + clips.size() + " clips, " + sessions + " sessions, 1 copying, 1 duplicate - OK");
    }

    @Test
    @Order(2)
    void mergeInAllThreeAudioModes() throws Exception {
        onFx(() -> {
            controller.setOutputDir(output);
            controller.setMergeMode(MergeMode.PER_SESSION);
            controller.setAudio(AudioMode.KEEP, MusicSettings.DEFAULT);
            start(controller.planMerge());
            return null;
        });
        waitForJobs(3);
        onFx(() -> {
            controller.setMergeMode(MergeMode.ALL_IN_ONE);
            controller.setAudio(AudioMode.REMOVE, MusicSettings.DEFAULT);
            start(controller.planMerge());
            return null;
        });
        waitForJobs(5); // one file per channel
        onFx(() -> {
            controller.setAudio(AudioMode.MUSIC, new MusicSettings(List.of(root.resolve("müzik 'test'.mp3").toString()),
                    true, 1.0, true, 2, 0.9, true, 0.2));
            start(controller.planMerge());
            return null;
        });
        waitForJobs(7);
        List<JobRecord> jobs = onFx(() -> List.copyOf(controller.jobs()));
        assertTrue(jobs.stream().allMatch(j -> j.getState() == JobState.DONE), "all merges done");
        ProbeService probe = new ProbeService(tools);
        MediaInfo keep = probe.probe(Path.of(jobs.get(0).output())).orElseThrow();
        assertEquals(18.0, keep.duration().toMillis() / 1000.0, 0.4, "session A = 3 x 6 s");
        assertEquals("aac", keep.audioCodec());
        MediaInfo removed = probe.probe(Path.of(jobs.get(3).output())).orElseThrow();
        assertFalse(removed.hasAudio(), "audio removed");
        MediaInfo music = probe.probe(Path.of(jobs.get(5).output())).orElseThrow();
        assertEquals("aac", music.audioCodec(), "background music");
        assertEquals(2, music.audioChannels(), "stereo music");
        assertTrue(Files.list(output).noneMatch(f -> f.getFileName().toString().contains(".part.")
                || f.getFileName().toString().endsWith(".work")), "no leftovers");
        report("merge: per session (keep), all-in-one per channel (remove), music loop+fade+mix - 7 files OK");
    }

    @Test
    @Order(3)
    void compressWithMusicAndStampStopReopenAndResume() throws Exception {
        waitFor("encoders", () -> controller.encodersProperty().get() != null);
        CompressOptions base = onFx(controller::compressOptions);
        assumeTrue(base != null, "no working encoder");
        onFx(() -> {
            controller.setMergeMode(MergeMode.PER_SESSION);
            controller.setCompressSource(false, null);
            controller.setCompressOptions(new CompressOptions(base.profile(), base.profile().defaultQuality(), 2, 1,
                    AudioMode.KEEP, MusicSettings.DEFAULT, true));
            AppController.CompressPlan plan = controller.planCompress();
            assertTrue(plan.ok(), String.valueOf(plan.errorKey()));
            controller.startCompress(plan.specs().subList(0, 1)); // session A, 18 s in 2 s segments
            return null;
        });
        JobRecord job = onFx(() -> controller.jobs().getLast());
        waitFor("first segments", () -> job.getCompress().getSegments().stream()
                .filter(s -> s.getState() == SegmentState.APPENDED).count() >= 2);
        onFx(() -> {
            controller.stopQueueNow();
            return null;
        });
        waitFor("stop", () -> !controller.isQueueRunning());
        assertEquals(JobState.PAUSED, job.getState());
        Path part = CompressRecovery.partFile(job.getCompress().getSpec());
        assertTrue(Files.size(part) > 0, "growing output exists and has content");
        long appended = job.getCompress().getSegments().stream().filter(s -> s.getState() == SegmentState.APPENDED).count();
        report("compress: stopped after " + appended + " segments, growing output " + Files.size(part) + " bytes");

        // simulate a crash on top: half-written append + job still RUNNING in the file
        onFx(() -> {
            controller.saveNow();
            return null;
        });
        var saved = ProjectStore.load(projectFile);
        JobRecord savedJob = saved.getJobs().stream().filter(j -> j.getId().equals(job.getId())).findFirst().orElseThrow();
        savedJob.setState(JobState.RUNNING);
        ProjectStore.save(saved, projectFile);
        Files.write(part, new byte[]{0, 0, 0, 64, 'm', 'o', 'o', 'f', 1, 2}, StandardOpenOption.APPEND);

        // reopen the project in a fresh controller, as after a restart
        onFx(() -> {
            controller.shutdown();
            controller = new AppController(new AppSettings(prefs));
            assertTrue(controller.initFfmpeg());
            List<RecoveryService.Issue> issues = controller.open(projectFile);
            assertEquals(1, issues.stream().filter(i -> i.kind() == RecoveryService.Kind.INTERRUPTED).count());
            controller.resumeInterrupted(issues, true);
            return null;
        });
        waitFor("resumed compression", () -> controller.jobs().stream()
                .filter(j -> j.getType() == JobType.COMPRESS).allMatch(j -> j.getState() == JobState.DONE));
        JobRecord done = onFx(() -> controller.jobs().stream().filter(j -> j.getType() == JobType.COMPRESS)
                .findFirst().orElseThrow());
        Path out = Path.of(done.output());
        MediaInfo m = new ProbeService(tools).probe(out).orElseThrow();
        assertEquals(18.0, m.duration().toMillis() / 1000.0, 0.5);
        assertEquals(540, frames(out), "18 s x 30 fps, every frame exactly once after stop + crash + resume");
        assertFalse(Files.exists(part), "growing output removed after the final mux");
        run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y", "-ss", "7", "-i", out.toString(), "-frames:v", "1",
                OUT.resolve("stamp-7s.png").toString()));
        report("compress: resumed after stop + simulated crash -> " + m.videoCodec() + " " + Files.size(out)
                + " bytes, 540/540 frames, date stamp frame saved (stamp-7s.png) - OK");
    }

    @Test
    @Order(4)
    void telemetryExportFromDjiSidecar() throws Exception {
        Path drone = Files.createDirectories(root.resolve("drone"));
        Path video = clip("DJI_0001.MP4", 3, drone);
        Files.writeString(drone.resolve("DJI_0001.SRT"), """
                1
                00:00:00,000 --> 00:00:01,000
                2026-11-01 12:00:00.000
                [latitude: 41.008200] [longitude: 28.978400] [rel_alt: 1.0 abs_alt: 50.0]

                2
                00:00:01,000 --> 00:00:02,000
                2026-11-01 12:00:01.000
                [latitude: 41.008300] [longitude: 28.978500] [rel_alt: 1.5 abs_alt: 50.5]
                """, StandardCharsets.UTF_8);
        var t = new TelemetryService(tools, ZoneOffset.UTC).read(video);
        assertEquals(2, t.positionCount());
        int n = TelemetryExport.gpx(t, "drone", null, OUT.resolve("drone.gpx"));
        assertEquals(2, n);
        report("telemetry: DJI sidecar -> " + n + " GPX points - OK");
    }

    @Test
    @Order(5)
    void languageSwitchAndFolderWatching() throws Exception {
        Messages.use("tr", List.of());
        onFx(() -> {
            controller.retranslate();
            return null;
        });
        assertTrue(Messages.get("merge.title").equals("Birleştir"));
        Messages.use("en", List.of());
        int before = onFx(() -> (int) controller.sessions().stream().mapToLong(s -> s.clips().size()).sum());
        clip("NO20261101-140000-000009F.mp4", 3, source); // a new clip arrives while the app runs
        waitFor("watcher rescan", () -> controller.sessions().stream()
                .mapToLong(s -> s.clips().size()).sum() > before, 120);
        report("i18n switch tr/en OK; folder watching picked up a new clip - OK");
    }

    // ----------------------------------------------------------------- helpers

    private static void start(AppController.MergePlan plan) throws Exception {
        assertTrue(plan.ok(), String.valueOf(plan.errorKey()));
        controller.startMerge(plan.specs());
    }

    private static void waitForJobs(int count) throws Exception {
        waitFor(count + " jobs", () -> controller.jobs().size() == count
                && controller.jobs().stream().allMatch(j -> j.getState().terminal()));
    }

    private static Path clip(String name, double seconds, Path dir) throws Exception {
        Path f = dir.resolve(name);
        run(List.of(tools.ffmpeg().toString(), "-v", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30:duration=" + seconds,
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000:duration=" + seconds,
                "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ac", "1", "-shortest", f.toString()));
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minusSeconds(3600)));
        return f;
    }

    private static int frames(Path f) throws Exception {
        Process p = new ProcessBuilder(tools.ffprobe().toString(), "-v", "error", "-select_streams", "v",
                "-count_packets", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", f.toString()).start();
        p.getOutputStream().close();
        String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        return Integer.parseInt(s);
    }

    private static void run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0, out);
    }

    private static void report(String line) {
        REPORT.add(line);
        System.out.println("E2E: " + line);
    }

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
        return f.get(60, TimeUnit.SECONDS);
    }

    private static boolean onFxQuiet(Supplier<Boolean> s) {
        try {
            return onFx(s::get);
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitFor(String what, Supplier<Boolean> condition) throws Exception {
        waitFor(what, condition, 180);
    }

    private static void waitFor(String what, Supplier<Boolean> condition, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (!onFxQuiet(condition::get)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(150);
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
