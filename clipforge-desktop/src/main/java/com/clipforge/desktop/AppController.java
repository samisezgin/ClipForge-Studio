package com.clipforge.desktop;

import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.compress.CompressOptions;
import com.clipforge.core.compress.CompressPlanner;
import com.clipforge.core.compress.CompressSampler;
import com.clipforge.core.compress.CompressSpec;
import com.clipforge.core.compress.EncoderCapabilities;
import com.clipforge.core.compress.EncoderProfile;
import com.clipforge.core.ffmpeg.FfmpegLocator;
import com.clipforge.core.ffmpeg.FfmpegTools;
import com.clipforge.core.ffmpeg.ProbeService;
import com.clipforge.core.ffmpeg.Progress;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.job.JobQueue;
import com.clipforge.core.merge.IncompatibleClipsException;
import com.clipforge.core.merge.MergeMode;
import com.clipforge.core.merge.MergeOptions;
import com.clipforge.core.merge.MergePlanner;
import com.clipforge.core.merge.MergeService;
import com.clipforge.core.merge.MergeSpec;
import com.clipforge.core.model.Clip;
import com.clipforge.core.model.Session;
import com.clipforge.core.project.Fingerprint;
import com.clipforge.core.project.JobRecord;
import com.clipforge.core.project.Project;
import com.clipforge.core.project.ProjectStore;
import com.clipforge.core.project.RecoveryService;
import com.clipforge.core.scan.ClipScanner;
import com.clipforge.core.scan.SessionGrouper;
import com.clipforge.core.scan.TimestampResolver;
import com.clipforge.core.util.DiskCheck;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Application state and actions, independent of the concrete widgets. All public methods must be called on the
 * JavaFX application thread; long-running work is moved to background threads internally.
 */
public final class AppController {

    private static final Logger LOG = Logger.getLogger(AppController.class.getName());

    /** Result of {@link #planMerge()}: either specs to run or a message explaining why not. */
    public record MergePlan(List<MergeSpec> specs, String errorKey, List<String> errorArgs) {
        static MergePlan error(String key, String... args) {
            return new MergePlan(List.of(), key, List.of(args));
        }

        public boolean ok() {
            return errorKey == null;
        }
    }

    private final AppSettings settings;
    private final Clock clock = Clock.systemDefaultZone();

    private Project project = new Project();
    private final ObjectProperty<Path> projectFile = new SimpleObjectProperty<>();
    private final BooleanProperty untitled = new SimpleBooleanProperty(true);
    private final ObservableList<String> folders = FXCollections.observableArrayList();
    private final ObservableList<Session> sessions = FXCollections.observableArrayList();
    private final ObservableList<JobRecord> jobs = FXCollections.observableArrayList();
    private final ObservableMap<String, Progress> progress = FXCollections.observableHashMap();
    private final Set<Path> selected = new HashSet<>();
    private final BooleanProperty scanning = new SimpleBooleanProperty();
    private final BooleanProperty queueRunning = new SimpleBooleanProperty();
    private final BooleanProperty queuePaused = new SimpleBooleanProperty();
    private final StringProperty status = new SimpleStringProperty("");
    private final ObjectProperty<FfmpegTools> tools = new SimpleObjectProperty<>();
    /** Encoder profiles that work on this machine; {@code null} while detection runs. */
    private final ObjectProperty<java.util.Set<EncoderProfile>> encoders = new SimpleObjectProperty<>();
    /** Incremented whenever the selection changes, so views can refresh summaries. */
    private final ObjectProperty<Integer> selectionVersion = new SimpleObjectProperty<>(0);
    private final ObjectProperty<Integer> projectVersion = new SimpleObjectProperty<>(0);
    private final javafx.beans.property.SimpleStringProperty outputDirProp = new javafx.beans.property.SimpleStringProperty();

    private JobQueue queue;
    private int scanGeneration;
    private boolean rescanRequested;
    private final PauseTransition saveDebounce = new PauseTransition(Duration.millis(800));

    public AppController(AppSettings settings) {
        this.settings = settings;
        saveDebounce.setOnFinished(e -> saveNow());
    }

    // ------------------------------------------------------------------ setup

    /** Locates FFmpeg; returns {@code false} if it was not found. */
    public boolean initFfmpeg() {
        String dir = settings.ffmpegDir();
        Optional<FfmpegTools> found = FfmpegLocator.locate(dir.isBlank() ? null : Path.of(dir));
        tools.set(found.orElse(null));
        detectEncoders();
        return found.isPresent();
    }

    /** Test-encodes with each profile in the background (NVENC needs a working NVIDIA driver). */
    private void detectEncoders() {
        FfmpegTools t = tools.get();
        if (t == null) {
            encoders.set(java.util.Set.of());
            return;
        }
        Thread.ofVirtual().start(() -> {
            java.util.Set<EncoderProfile> found = EncoderCapabilities.detect(t);
            Platform.runLater(() -> encoders.set(java.util.Set.copyOf(found)));
        });
    }

    public boolean useFfmpegDir(Path dir) {
        Optional<FfmpegTools> found = FfmpegLocator.fromDir(dir);
        if (found.isPresent()) {
            settings.setFfmpegDir(dir.toString());
            tools.set(found.get());
            detectEncoders();
            rebuildQueue();
            // jobs the user already chose to resume (e.g. in the recovery dialog) start now that FFmpeg exists
            if (queue != null && !queue.isPaused() && project.getJobs().stream()
                    .anyMatch(j -> j.getState() == com.clipforge.core.project.JobState.PENDING)) {
                queueRunning.set(true);
                queue.start();
            }
        }
        return found.isPresent();
    }

    /** Opens the last project (or the untitled autosave) and returns recovery issues to show to the user. */
    public List<RecoveryService.Issue> openLastProject() {
        String last = settings.lastProject();
        Path file = last.isBlank() ? untitledFile() : Path.of(last);
        if (Files.isRegularFile(file)) {
            try {
                load(file);
                if (!untitled.get()) {
                    settings.addRecentProject(file.toString());
                }
                return RecoveryService.analyze(project);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Cannot open last project " + file, e);
                setStatus("status.openFailed", file.getFileName(), e.getMessage());
                if (file.equals(untitledFile())) {
                    keepUnreadableAutosave(file);
                }
            }
        }
        startEmptyProject();
        return List.of();
    }

    /** An unreadable autosave may be the only copy of the user's work: keep it under another name. */
    private void keepUnreadableAutosave(Path file) {
        try {
            Path bad = file.resolveSibling(file.getFileName() + ".bad-" + clock.millis());
            Files.move(file, bad);
            LOG.warning("Unreadable autosave kept as " + bad);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot rename unreadable autosave", e);
        }
    }

    private void startEmptyProject() {
        project = new Project();
        projectFile.set(untitledFile());
        untitled.set(true);
        settings.setLastProject("");
        afterProjectChanged();
    }

    // ------------------------------------------------------------------ project files

    /** {@code true} if switching projects would drop resumable jobs of an untitled project. */
    public boolean switchingLosesJobs() {
        return untitled.get() && project.hasUnfinishedJobs();
    }

    /** Starts an empty project. The caller confirms first if {@link #switchingLosesJobs()}. */
    public void newProject() {
        if (isQueueRunning()) {
            throw new IllegalStateException("jobs are running");
        }
        saveNow(); // a named project keeps its paused jobs in its own file
        boolean wasUntitled = untitled.get();
        startEmptyProject();
        if (wasUntitled) {
            try {
                Files.deleteIfExists(untitledFile()); // the user discarded the untitled project
            } catch (IOException ignored) {
                // overwritten by the next autosave anyway
            }
        }
    }

    /** Opens a project file and returns interrupted work found in it. */
    public List<RecoveryService.Issue> open(Path file) throws IOException {
        if (isQueueRunning()) {
            throw new IOException(Messages.get("error.busy"));
        }
        saveNow();
        try {
            load(file);
        } catch (IOException e) {
            settings.removeRecentProject(file.toString());
            throw e;
        }
        settings.addRecentProject(file.toString());
        return RecoveryService.analyze(project);
    }

    private void load(Path file) throws IOException {
        Project p = ProjectStore.load(file);
        project = p;
        projectFile.set(file);
        boolean isUntitled = file.equals(untitledFile());
        untitled.set(isUntitled);
        settings.setLastProject(isUntitled ? "" : file.toString());
        afterProjectChanged();
    }

    public void saveAs(Path file) throws IOException {
        Path target = ProjectStore.withExtension(file);
        synchronized (project) {
            syncSelection();
            ProjectStore.save(project, target);
        }
        projectFile.set(target);
        untitled.set(false);
        settings.setLastProject(target.toString());
        settings.addRecentProject(target.toString());
    }

    /** Saves to the current file (the untitled autosave if the project has no name yet). */
    public void saveNow() {
        Path f = projectFile.get() != null ? projectFile.get() : untitledFile();
        try {
            synchronized (project) {
                syncSelection();
                ProjectStore.save(project, f);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Autosave failed", e);
            setStatus("status.saveFailed", e.getMessage());
        }
    }

    /** Copies the UI selection into the project. Caller holds the project monitor. */
    private void syncSelection() {
        project.getSelectedClips().clear();
        selected.forEach(p -> project.getSelectedClips().add(p.toString()));
    }

    private void scheduleSave() {
        saveDebounce.playFromStart();
    }

    private void afterProjectChanged() {
        folders.setAll(project.getFolders());
        jobs.setAll(project.getJobs());
        progress.clear();
        selected.clear();
        project.getSelectedClips().forEach(s -> selected.add(Path.of(s)));
        sessions.clear();
        queueRunning.set(false);
        queuePaused.set(project.getJobs().stream()
                .anyMatch(j -> j.getState() == com.clipforge.core.project.JobState.PAUSED));
        scanGeneration++; // results of a scan started for the previous project are discarded
        outputDirProp.set(project.getOutputDir());
        projectVersion.set(projectVersion.get() + 1);
        rebuildQueue();
        rescan();
    }

    private void rebuildQueue() {
        if (queue != null && queue.isRunning()) {
            return;
        }
        FfmpegTools t = tools.get();
        if (t == null) {
            queue = null;
            return;
        }
        java.util.Map<com.clipforge.core.project.JobType, com.clipforge.core.job.JobExecutor> executors = java.util.Map.of(
                com.clipforge.core.project.JobType.MERGE, new com.clipforge.core.job.MergeExecutor(new MergeService(t)),
                com.clipforge.core.project.JobType.COMPRESS, new com.clipforge.core.compress.SegmentedEncoder(t));
        queue = new JobQueue(project, executors, this::saveFromWorker, new JobQueue.Listener() {
            @Override
            public void jobChanged(JobRecord job) {
                Platform.runLater(() -> refreshJob(job));
            }

            @Override
            public void progress(JobRecord job, Progress p) {
                Platform.runLater(() -> progress.put(job.getId(), p));
            }

            @Override
            public void idle() {
                Platform.runLater(() -> {
                    queueRunning.set(false);
                    queuePaused.set(queue != null && queue.isPaused());
                });
            }
        }, clock);
    }

    private void saveFromWorker() {
        Path f = projectFile.get() != null ? projectFile.get() : untitledFile();
        Project p = project;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                synchronized (p) {
                    ProjectStore.save(p, f);
                }
                return;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Saving job state failed (attempt " + (attempt + 1) + ")", e);
            }
        }
        Platform.runLater(() -> setStatus("status.saveFailed", f.getFileName()));
    }

    private void refreshJob(JobRecord job) {
        int i = jobs.indexOf(job);
        if (i >= 0) {
            jobs.set(i, job); // triggers cell update
        } else if (project.getJobs().contains(job)) {
            jobs.add(job);
        }
    }

    // ------------------------------------------------------------------ folders and scanning

    public void addFolder(Path dir) {
        String s = dir.toAbsolutePath().toString();
        boolean added;
        synchronized (project) {
            added = !project.getFolders().contains(s) && project.getFolders().add(s);
        }
        if (added) {
            folders.setAll(project.getFolders());
            settings.setLastFolder(s);
            scheduleSave();
            rescan();
        }
    }

    public void removeFolder(String dir) {
        synchronized (project) {
            project.getFolders().remove(dir);
        }
        folders.setAll(project.getFolders());
        scheduleSave();
        rescan();
    }

    public void rescan() {
        FfmpegTools t = tools.get();
        if (t == null || project.getFolders().isEmpty()) {
            sessions.clear();
            return;
        }
        if (scanning.get()) {
            rescanRequested = true; // run again when the current scan ends, e.g. after adding a folder
            return;
        }
        scanning.set(true);
        rescanRequested = false;
        int generation = ++scanGeneration;
        setStatus("status.scanning", 0, "?");
        List<Path> dirs = project.getFolders().stream().map(Path::of).toList();
        Thread.ofVirtual().start(() -> {
            try {
                ClipScanner scanner = new ClipScanner(new ProbeService(t),
                        new TimestampResolver(ZoneId.systemDefault()), clock);
                List<Clip> clips = scanner.scan(dirs, (done, total) ->
                        Platform.runLater(() -> setStatus("status.scanning", done, total)));
                List<Session> grouped = new SessionGrouper(SessionGrouper.DEFAULT_TOLERANCE).group(clips);
                Platform.runLater(() -> applyScan(generation, clips, grouped));
            } catch (IOException e) {
                Platform.runLater(() -> {
                    scanning.set(false);
                    setStatus("status.scanFailed", e.getMessage());
                    rescanIfRequested();
                });
            }
        });
    }

    private void rescanIfRequested() {
        if (rescanRequested) {
            rescanRequested = false;
            rescan();
        }
    }

    private void applyScan(int generation, List<Clip> clips, List<Session> grouped) {
        scanning.set(false);
        if (generation != scanGeneration) {
            // the project or its folders changed while scanning: these results are stale
            rescan();
            return;
        }
        Set<Path> present = new HashSet<>();
        int copying = 0;
        for (Clip c : clips) {
            present.add(c.path());
            String key = c.path().toString();
            boolean isNew;
            synchronized (project) {
                isNew = c.selectable() && project.getSeenClips().add(key);
            }
            if (isNew) {
                selected.add(c.path()); // newly found ready clips are selected by default
            }
            if (!c.selectable()) {
                selected.remove(c.path());
                if (c.status() == com.clipforge.core.model.ClipStatus.COPYING) {
                    copying++;
                }
            }
        }
        selected.retainAll(present);
        sessions.setAll(grouped);
        if (copying > 0) {
            setStatus("status.scannedCopying", clips.size(), grouped.size(), copying);
        } else {
            setStatus("status.scanned", clips.size(), grouped.size());
        }
        bumpSelection();
        scheduleSave();
        rescanIfRequested();
    }

    // ------------------------------------------------------------------ selection

    public boolean isSelected(Clip c) {
        return selected.contains(c.path());
    }

    public void setSelected(Clip c, boolean value) {
        if (!c.selectable()) {
            return;
        }
        boolean changed = value ? selected.add(c.path()) : selected.remove(c.path());
        if (changed) {
            bumpSelection();
            scheduleSave();
        }
    }

    public void setSelected(List<Clip> clips, boolean value) {
        boolean changed = false;
        for (Clip c : clips) {
            if (c.selectable()) {
                changed |= value ? selected.add(c.path()) : selected.remove(c.path());
            }
        }
        if (changed) {
            bumpSelection();
            scheduleSave();
        }
    }

    public void setAllSelected(boolean value) {
        for (Session s : sessions) {
            for (Clip c : s.clips()) {
                if (c.selectable()) {
                    if (value) {
                        selected.add(c.path());
                    } else {
                        selected.remove(c.path());
                    }
                }
            }
        }
        bumpSelection();
        scheduleSave();
    }

    private void bumpSelection() {
        selectionVersion.set(selectionVersion.get() + 1);
    }

    public List<Clip> selectedClips() {
        List<Clip> list = new ArrayList<>();
        for (Session s : sessions) {
            for (Clip c : s.clips()) {
                if (selected.contains(c.path()) && c.selectable()) {
                    list.add(c);
                }
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ merge settings

    public MergeMode mergeMode() {
        return project.getMergeMode();
    }

    public void setMergeMode(MergeMode mode) {
        if (mode == project.getMergeMode()) {
            return;
        }
        synchronized (project) {
            project.setMergeMode(mode);
        }
        bumpSelection(); // grouping of the selection changed (compress summary)
        scheduleSave();
    }

    public MergeOptions mergeOptions() {
        return project.getMergeOptions();
    }

    public void setMergeOptions(MergeOptions options) {
        if (options.equals(project.getMergeOptions())) {
            return;
        }
        synchronized (project) {
            project.setMergeOptions(options);
        }
        scheduleSave();
    }

    public String outputDir() {
        return project.getOutputDir();
    }

    /** If no output folder is set, use the last one or {@code ~/Videos/ClipForge}. */
    private void ensureOutputDir() {
        String cur = project.getOutputDir();
        if (cur != null && !cur.isBlank()) {
            return;
        }
        String last = settings.lastOutputDir();
        setOutputDir(last.isBlank() ? Path.of(System.getProperty("user.home"), "Videos", "ClipForge") : Path.of(last));
    }

    public javafx.beans.property.ReadOnlyStringProperty outputDirProperty() {
        return outputDirProp;
    }

    public void setOutputDir(Path dir) {
        synchronized (project) {
            project.setOutputDir(dir == null ? null : dir.toAbsolutePath().toString());
        }
        outputDirProp.set(project.getOutputDir());
        if (dir != null) {
            settings.setLastOutputDir(project.getOutputDir());
        }
        scheduleSave();
    }

    // ------------------------------------------------------------------ audio settings (merge and compression)

    public com.clipforge.core.audio.AudioMode audioMode() {
        return project.getAudioMode();
    }

    public com.clipforge.core.audio.MusicSettings music() {
        return project.getMusic();
    }

    public void setAudio(com.clipforge.core.audio.AudioMode mode, com.clipforge.core.audio.MusicSettings music) {
        if (mode == project.getAudioMode() && music.equals(project.getMusic())) {
            return;
        }
        synchronized (project) {
            project.setAudioMode(mode);
            project.setMusic(music);
        }
        scheduleSave();
    }

    /** Validates the music selection; returns an i18n error key or {@code null}. */
    private String audioError() {
        if (project.getAudioMode() != com.clipforge.core.audio.AudioMode.MUSIC) {
            return null;
        }
        List<String> tracks = project.getMusic().tracks();
        if (tracks.isEmpty()) {
            return "error.noMusic";
        }
        for (String t : tracks) {
            if (!Files.isRegularFile(Path.of(t))) {
                return "error.musicMissing";
            }
        }
        return null;
    }

    /** Total length of the music playlist in seconds, measured in the background. */
    public void measureMusic(java.util.function.Consumer<Double> result) {
        FfmpegTools t = tools.get();
        List<String> tracks = project.getMusic().tracks();
        if (t == null || tracks.isEmpty()) {
            result.accept(0.0);
            return;
        }
        Thread.ofVirtual().start(() -> {
            double total;
            try {
                total = new com.clipforge.core.audio.MusicRenderer(t).durations(tracks).stream()
                        .mapToDouble(Double::doubleValue).sum();
            } catch (IOException e) {
                total = -1;
            }
            double v = total;
            Platform.runLater(() -> result.accept(v));
        });
    }

    // ------------------------------------------------------------------ compression settings

    public ObjectProperty<java.util.Set<EncoderProfile>> encodersProperty() {
        return encoders;
    }

    /** Saved options, or defaults for the best available encoder; {@code null} if no encoder works. */
    public CompressOptions compressOptions() {
        CompressOptions o = project.getCompressOptions();
        java.util.Set<EncoderProfile> av = encoders.get();
        if (av == null) {
            return null; // still detecting: nothing may start before the encoder is verified
        }
        if (o != null && av.contains(o.profile())) {
            return o;
        }
        EncoderProfile p = EncoderCapabilities.preferred(av);
        return p == null ? null : CompressOptions.defaults(p);
    }

    /** {@code true} if the project's saved encoder does not work on this machine and a default replaces it. */
    public boolean savedEncoderUnavailable() {
        CompressOptions o = project.getCompressOptions();
        java.util.Set<EncoderProfile> av = encoders.get();
        return o != null && av != null && !av.contains(o.profile());
    }

    public void setCompressOptions(CompressOptions o) {
        synchronized (project) {
            project.setCompressOptions(o);
        }
        scheduleSave();
    }

    public boolean compressFromFile() {
        return project.isCompressFromFile();
    }

    public String compressFile() {
        return project.getCompressFile();
    }

    public void setCompressSource(boolean fromFile, Path file) {
        synchronized (project) {
            project.setCompressFromFile(fromFile);
            project.setCompressFile(file == null ? null : file.toAbsolutePath().toString());
        }
        scheduleSave();
    }

    /** Result of {@link #planCompress()}. */
    public record CompressPlan(List<CompressSpec> specs, String errorKey, List<String> errorArgs) {
        static CompressPlan error(String key, String... args) {
            return new CompressPlan(List.of(), key, List.of(args));
        }

        public boolean ok() {
            return errorKey == null;
        }
    }

    /**
     * Builds compression jobs: for the selected clips one job per output of the current merge mode (the clips are
     * joined and compressed in one pass), or one job for the chosen file.
     */
    /** Plans in the background (probing a 50 GB file or a slow drive must not freeze the UI). */
    public void planCompressAsync(java.util.function.Consumer<CompressPlan> result) {
        ensureOutputDir();
        String audioErr = audioError();
        if (audioErr != null) {
            result.accept(CompressPlan.error(audioErr));
            return;
        }
        CompressInputs in = new CompressInputs(tools.get(), withAudio(compressOptions()), project.getOutputDir(),
                project.isCompressFromFile(), project.getCompressFile(), List.copyOf(sessions), Set.copyOf(selected),
                project.getMergeMode(), project.getMergeOptions(), reservedOutputs(), selectedClips().isEmpty());
        Thread.ofVirtual().start(() -> {
            CompressPlan plan;
            try {
                plan = planCompress(in);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Planning compression failed", e);
                plan = CompressPlan.error("error.internal", e.getClass().getSimpleName());
            }
            CompressPlan p = plan;
            Platform.runLater(() -> result.accept(p));
        });
    }

    private CompressOptions withAudio(CompressOptions o) {
        return o == null ? null : o.withAudio(project.getAudioMode(), project.getMusic());
    }

    /** Snapshot of everything planning needs, taken on the FX thread. */
    private record CompressInputs(FfmpegTools tools, CompressOptions options, String outputDir, boolean fromFile,
                                  String file, List<Session> sessions, Set<Path> selected, MergeMode mode,
                                  MergeOptions mergeOptions, List<Path> reserved, boolean nothingSelected) {
    }

    /** Synchronous planning on the current thread (tests). */
    public CompressPlan planCompress() {
        ensureOutputDir();
        String audioErr = audioError();
        if (audioErr != null) {
            return CompressPlan.error(audioErr);
        }
        return planCompress(new CompressInputs(tools.get(), withAudio(compressOptions()), project.getOutputDir(),
                project.isCompressFromFile(), project.getCompressFile(), List.copyOf(sessions), Set.copyOf(selected),
                project.getMergeMode(), project.getMergeOptions(), reservedOutputs(), selectedClips().isEmpty()));
    }

    private static CompressPlan planCompress(CompressInputs in) {
        FfmpegTools t = in.tools();
        CompressOptions o = in.options();
        if (t == null) {
            return CompressPlan.error("error.noFfmpeg");
        }
        if (o == null) {
            return CompressPlan.error("error.noEncoder");
        }
        String out = in.outputDir();
        if (out == null || out.isBlank()) {
            return CompressPlan.error("error.noOutputDir");
        }
        Path outDir = Path.of(out);
        try {
            Files.createDirectories(outDir);
            List<Path> reserved = in.reserved();
            List<CompressSpec> specs = new ArrayList<>();
            if (in.fromFile()) {
                String f = in.file();
                if (f == null || !Files.isRegularFile(Path.of(f))) {
                    return CompressPlan.error("error.noCompressFile");
                }
                Path file = Path.of(f);
                var media = new ProbeService(t).probe(file);
                if (media.isEmpty() || !media.get().hasVideo()) {
                    return CompressPlan.error("error.notVideo", file.getFileName().toString());
                }
                specs.add(CompressPlanner.forFile(file, Files.size(file), media.get(), outDir, o, reserved));
            } else {
                if (in.nothingSelected()) {
                    return CompressPlan.error("error.noSelection");
                }
                java.util.Map<Path, Clip> byPath = new java.util.HashMap<>();
                in.sessions().forEach(s -> s.clips().forEach(c -> byPath.put(c.path(), c)));
                for (MergeSpec m : MergePlanner.plan(in.sessions(), in.selected(), in.mode(), outDir,
                        in.mergeOptions(), reserved)) {
                    List<Clip> clips = m.inputs().stream().map(i -> byPath.get(Path.of(i))).toList();
                    List<Path> taken = new ArrayList<>(reserved);
                    specs.forEach(s -> taken.add(Path.of(s.output())));
                    specs.add(CompressPlanner.forClips(clips, outDir, o, taken));
                }
            }
            // the growing output and the final file coexist for a moment: plan for twice a conservative estimate
            long input = specs.stream().mapToLong(CompressSpec::totalBytes).sum();
            DiskCheck.Result disk = DiskCheck.check(outDir, input, input / 2);
            if (!disk.enoughSpace()) {
                return CompressPlan.error("error.diskSpace", com.clipforge.core.util.Formats.size(disk.requiredBytes()),
                        com.clipforge.core.util.Formats.size(disk.usableBytes()));
            }
            if (disk.exceedsFileSizeLimit()) {
                return CompressPlan.error("error.fat32", disk.fileSystem());
            }
            return new CompressPlan(specs, null, List.of());
        } catch (IncompatibleClipsException e) {
            return CompressPlan.error("error.incompatible", String.join(", ", e.formats()));
        } catch (IOException e) {
            return CompressPlan.error("error.io", String.valueOf(e.getMessage()));
        }
    }

    public void startCompress(List<CompressSpec> specs) throws IOException {
        if (queue == null) {
            throw new IOException(Messages.get("error.noFfmpeg"));
        }
        submitCompress(createCompressJobs(specs));
    }

    /** Fingerprints the sources in the background, then queues the jobs on the FX thread. */
    public void startCompressAsync(List<CompressSpec> specs, java.util.function.Consumer<String> failed) {
        if (queue == null) {
            failed.accept(Messages.get("error.noFfmpeg"));
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                List<JobRecord> jobs = createCompressJobs(specs);
                Platform.runLater(() -> {
                    submitCompress(jobs);
                    setStatus("status.compressQueued", jobs.size());
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> failed.accept(Messages.get("error.io", String.valueOf(e.getMessage()))));
            }
        });
    }

    private List<JobRecord> createCompressJobs(List<CompressSpec> specs) throws IOException {
        List<JobRecord> created = new ArrayList<>();
        for (CompressSpec s : specs) {
            List<Fingerprint> fps = new ArrayList<>();
            for (String in : s.inputs()) {
                fps.add(Fingerprint.of(Path.of(in)));
            }
            created.add(JobRecord.compress(s, fps, clock.instant()));
        }
        return created;
    }

    private void submitCompress(List<JobRecord> created) {
        if (queue == null) {
            return;
        }
        saveNow();
        queue.submit(created);
        queue.unpause();
        queueRunning.set(true);
        queuePaused.set(project.getJobs().stream()
                .anyMatch(j -> j.getState() == com.clipforge.core.project.JobState.PAUSED));
    }

    /** Encodes a short sample of the first planned job in the background. */
    public void sample(CompressSpec spec, java.util.function.Consumer<CompressSampler.Result> done,
                       java.util.function.Consumer<String> failed) {
        FfmpegTools t = tools.get();
        Thread.ofVirtual().start(() -> {
            try {
                CompressSampler.Result r = new CompressSampler(t).sample(spec, 60, null);
                Platform.runLater(() -> done.accept(r));
            } catch (Exception e) {
                // any failure must reach the UI, otherwise the Sample button would stay disabled
                LOG.log(Level.WARNING, "Sample failed", e);
                Platform.runLater(() -> failed.accept(String.valueOf(e.getMessage())));
            }
        });
    }

    private List<Path> reservedOutputs() {
        synchronized (project) {
            return project.getJobs().stream().filter(j -> j.getState().unfinished() && j.output() != null)
                    .map(j -> Path.of(j.output())).toList();
        }
    }

    // ------------------------------------------------------------------ jobs

    /** Validates the selection and builds merge specs without starting anything. */
    public MergePlan planMerge() {
        if (tools.get() == null) {
            return MergePlan.error("error.noFfmpeg");
        }
        if (selectedClips().isEmpty()) {
            return MergePlan.error("error.noSelection");
        }
        String audioErr = audioError();
        if (audioErr != null) {
            return MergePlan.error(audioErr);
        }
        ensureOutputDir();
        String out = project.getOutputDir();
        if (out == null || out.isBlank()) {
            return MergePlan.error("error.noOutputDir");
        }
        Path outDir = Path.of(out);
        try {
            Files.createDirectories(outDir);
            List<Path> reserved;
            synchronized (project) {
                reserved = project.getJobs().stream().filter(j -> j.getState().unfinished())
                        .map(j -> Path.of(j.output())).toList();
            }
            List<MergeSpec> specs = MergePlanner.plan(sessions, selected, project.getMergeMode(), outDir,
                    project.getMergeOptions().withAudio(project.getAudioMode(), project.getMusic()), reserved);
            long total = specs.stream().mapToLong(MergeSpec::totalBytes).sum();
            long largest = specs.stream().mapToLong(MergeSpec::totalBytes).max().orElse(0);
            DiskCheck.Result disk = DiskCheck.check(outDir, total, largest);
            if (!disk.enoughSpace()) {
                return MergePlan.error("error.diskSpace",
                        com.clipforge.core.util.Formats.size(disk.requiredBytes()),
                        com.clipforge.core.util.Formats.size(disk.usableBytes()));
            }
            if (disk.exceedsFileSizeLimit()) {
                return MergePlan.error("error.fat32", disk.fileSystem());
            }
            return new MergePlan(specs, null, List.of());
        } catch (IncompatibleClipsException e) {
            return MergePlan.error("error.incompatible", String.join(", ", e.formats()));
        } catch (IOException e) {
            return MergePlan.error("error.io", String.valueOf(e.getMessage()));
        }
    }

    public void startMerge(List<MergeSpec> specs) throws IOException {
        List<JobRecord> created = new ArrayList<>();
        for (MergeSpec s : specs) {
            List<Fingerprint> fps = new ArrayList<>();
            for (String in : s.inputs()) {
                fps.add(Fingerprint.of(Path.of(in)));
            }
            created.add(JobRecord.merge(s, fps, clock.instant()));
        }
        if (queue == null) {
            throw new IOException(Messages.get("error.noFfmpeg"));
        }
        saveNow();
        // start the new jobs only; jobs the user paused earlier stay paused until "Resume"
        queue.submit(created);
        queue.unpause();
        queueRunning.set(true);
        queuePaused.set(project.getJobs().stream()
                .anyMatch(j -> j.getState() == com.clipforge.core.project.JobState.PAUSED));
    }

    public void pauseQueue() {
        if (queue != null) {
            boolean running = queue.isRunning();
            queue.pause();
            queuePaused.set(true);
            if (running) {
                setStatus("status.pausing"); // lossless: running segments finish first
            }
        }
    }

    public void stopQueueNow() {
        if (queue != null) {
            queue.stopNow();
            queuePaused.set(true);
        }
    }

    public void resumeQueue() {
        if (queue != null) {
            queuePaused.set(false);
            queueRunning.set(true);
            queue.resume();
        }
    }

    public void cancelJob(JobRecord job) {
        if (queue != null) {
            queue.cancel(job);
            refreshJob(job);
        }
    }

    /** Removes finished/failed/cancelled jobs from the list. */
    public void clearFinishedJobs() {
        List<JobRecord> removed;
        synchronized (project) {
            removed = project.getJobs().stream().filter(j -> j.getState().terminal()).toList();
            project.getJobs().removeAll(removed);
        }
        // failed or cancelled compression jobs may still hold a growing output and segments
        removed.stream().filter(j -> j.getState() != com.clipforge.core.project.JobState.DONE)
                .forEach(com.clipforge.core.compress.CompressRecovery::discard);
        jobs.setAll(project.getJobs());
        progress.keySet().retainAll(project.getJobs().stream().map(JobRecord::getId).toList());
        saveNow();
    }

    /** Applies the user's recovery decision. */
    public void resumeInterrupted(List<RecoveryService.Issue> issues, boolean startNow) {
        int failed = 0;
        for (RecoveryService.Issue i : issues) {
            if (i.kind() == RecoveryService.Kind.OUTPUT_MISSING) {
                continue;
            }
            synchronized (project) {
                try {
                    if (i.kind() == RecoveryService.Kind.SOURCE_CHANGED) {
                        RecoveryService.acceptChangedSources(i.job());
                        if (i.job().getCompress() != null) {
                            // segments encoded from the old source must not be joined with new ones
                            com.clipforge.core.compress.CompressRecovery.restart(i.job().getCompress());
                        }
                    }
                    RecoveryService.prepareResume(i.job(), clock.instant());
                    if (!startNow && i.job().getState() == com.clipforge.core.project.JobState.PENDING) {
                        i.job().setState(com.clipforge.core.project.JobState.PAUSED);
                    }
                } catch (IOException e) {
                    // e.g. an orphaned FFmpeg from the crashed session still holds the partial file
                    LOG.log(Level.WARNING, "Cannot prepare job for resume", e);
                    i.job().fail("error.io", null, String.valueOf(e.getMessage()));
                    failed++;
                }
            }
        }
        if (failed > 0) {
            setStatus("status.recoveryFailed", failed);
        }
        jobs.setAll(project.getJobs());
        saveNow();
        if (queue == null) {
            return;
        }
        if (startNow) {
            queueRunning.set(true);
            queue.start();
        } else {
            queue.pause();
            queuePaused.set(true);
        }
    }

    public void cancelJobs(List<RecoveryService.Issue> issues) {
        synchronized (project) {
            for (RecoveryService.Issue i : issues) {
                i.job().setState(com.clipforge.core.project.JobState.CANCELLED);
                com.clipforge.core.compress.CompressRecovery.discard(i.job());
                if (i.job().output() != null) {
                    try {
                        Files.deleteIfExists(com.clipforge.core.util.OutputNaming.partOf(Path.of(i.job().output())));
                    } catch (IOException ignored) {
                        // a leftover .part file is harmless
                    }
                }
            }
        }
        jobs.setAll(project.getJobs());
        saveNow();
    }

    /** Waits up to {@code timeout} for the queue to become idle (used by "pause and exit"). */
    public boolean awaitQueueIdle(java.time.Duration timeout) throws InterruptedException {
        return queue == null || queue.awaitIdle(timeout);
    }

    public boolean isQueueRunning() {
        return queue != null && queue.isRunning();
    }

    // ------------------------------------------------------------------ status line

    private String statusKey;
    private Object[] statusArgs = new Object[0];

    /** Sets the status line from an i18n key so it can be re-translated when the language changes. */
    public void setStatus(String key, Object... args) {
        statusKey = key;
        statusArgs = args;
        status.set(Messages.get(key, args));
    }

    public void retranslate() {
        if (statusKey != null) {
            status.set(Messages.get(statusKey, statusArgs));
        }
    }

    /** Incremented when a different project is loaded, so views reload their settings. */
    public ObjectProperty<Integer> projectVersionProperty() {
        return projectVersion;
    }

    // ------------------------------------------------------------------ accessors

    public Path untitledFile() {
        return AppSettings.homeDir().resolve("untitled." + ProjectStore.EXTENSION);
    }

    public AppSettings settings() {
        return settings;
    }

    public ObjectProperty<Path> projectFileProperty() {
        return projectFile;
    }

    public ReadOnlyBooleanProperty untitledProperty() {
        return untitled;
    }

    public ObservableList<String> folders() {
        return folders;
    }

    public ObservableList<Session> sessions() {
        return sessions;
    }

    public ObservableList<JobRecord> jobs() {
        return jobs;
    }

    public ObservableMap<String, Progress> progress() {
        return progress;
    }

    public BooleanProperty scanningProperty() {
        return scanning;
    }

    public BooleanProperty queueRunningProperty() {
        return queueRunning;
    }

    public BooleanProperty queuePausedProperty() {
        return queuePaused;
    }

    public StringProperty statusProperty() {
        return status;
    }

    public ObjectProperty<FfmpegTools> toolsProperty() {
        return tools;
    }

    public ObjectProperty<Integer> selectionVersionProperty() {
        return selectionVersion;
    }
}
