package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.ClipForge;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.project.ProjectStore;
import com.clipforge.core.project.RecoveryService;
import com.clipforge.desktop.AppController;
import com.clipforge.desktop.AppSettings;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Main window layout: menu, toolbar, folder chips, clip tree + merge panel, job queue, status bar. */
public final class MainView {

    private final AppController controller;
    private final Stage stage;
    private final HostServices host;
    private final Consumer<String> onLanguageChange;
    private final Consumer<Boolean> onThemeChange;
    private final BorderPane root = new BorderPane();
    private final FlowPane folderChips = new FlowPane(6, 6);
    private final ListenerBag listeners = new ListenerBag();
    private OutputFolderBox outputBox;
    private CompressPanel compressPanel;

    public MainView(AppController controller, Stage stage, HostServices host,
                    Consumer<String> onLanguageChange, Consumer<Boolean> onThemeChange) {
        this.controller = controller;
        this.stage = stage;
        this.host = host;
        this.onLanguageChange = onLanguageChange;
        this.onThemeChange = onThemeChange;
        build();
    }

    public Parent root() {
        return root;
    }

    /** Detaches this view from the controller; call before replacing it (e.g. on language change). */
    public void dispose() {
        listeners.dispose();
        stage.titleProperty().unbind();
    }

    private void build() {
        VBox top = new VBox(menuBar(), toolBar(), folderBar());
        root.setTop(top);

        ClipTreeView tree = new ClipTreeView(controller, listeners);
        outputBox = new OutputFolderBox(controller, listeners);
        MergePanel merge = new MergePanel(controller, listeners, this::merge);
        compressPanel = new CompressPanel(controller, listeners, start -> {
            if (start) {
                compress();
            } else {
                sample();
            }
        });
        javafx.scene.control.Tab mergeTab = new javafx.scene.control.Tab(Messages.get("merge.title"), merge);
        javafx.scene.control.Tab compressTab = new javafx.scene.control.Tab(Messages.get("compress.title"), compressPanel);
        javafx.scene.control.TabPane tabs = new javafx.scene.control.TabPane(mergeTab, compressTab);
        tabs.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);
        AudioPanel audio = new AudioPanel(controller, listeners);
        VBox side = new VBox(10, outputBox, audio, tabs);
        side.setPadding(new Insets(12));
        side.getStyleClass().add("side-panel");
        javafx.scene.control.ScrollPane sideScroll = new javafx.scene.control.ScrollPane(side);
        sideScroll.setFitToWidth(true);
        sideScroll.setMinWidth(320);
        sideScroll.setPrefWidth(360);
        sideScroll.getStyleClass().addAll("edge-to-edge", "side-panel");
        SplitPane split = new SplitPane(tree, sideScroll);
        split.setDividerPositions(0.7);
        SplitPane.setResizableWithParent(sideScroll, false);

        JobsView jobs = new JobsView(controller, host, listeners);
        SplitPane vertical = new SplitPane(split, jobs);
        vertical.setOrientation(Orientation.VERTICAL);
        vertical.setDividerPositions(0.7);
        root.setCenter(vertical);
        root.setBottom(statusBar());

        stage.titleProperty().bind(Bindings.createStringBinding(() -> {
            Path f = controller.projectFileProperty().get();
            String name = controller.untitledProperty().get() || f == null
                    ? Messages.get("project.untitled") : f.getFileName().toString();
            return Messages.get("window.title", name, ClipForge.NAME);
        }, controller.projectFileProperty(), controller.untitledProperty()));
    }

    // ---------------------------------------------------------------- menus & toolbar

    private MenuBar menuBar() {
        Menu file = new Menu(Messages.get("menu.file"));
        MenuItem newP = item("menu.file.new", "Shortcut+N", this::newProject);
        MenuItem open = item("menu.file.open", "Shortcut+O", this::openProject);
        Menu recent = new Menu(Messages.get("menu.file.recent"));
        recent.setOnShowing(e -> fillRecent(recent));
        recent.getItems().add(new MenuItem("…"));
        MenuItem save = item("menu.file.save", "Shortcut+S", this::save);
        MenuItem saveAs = item("menu.file.saveAs", "Shortcut+Shift+S", this::saveAs);
        MenuItem exit = item("menu.file.exit", null, () -> stage.fireEvent(
                new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
        file.getItems().addAll(newP, open, recent, new SeparatorMenuItem(), save, saveAs,
                new SeparatorMenuItem(), exit);

        Menu view = new Menu(Messages.get("menu.view"));
        Menu language = new Menu(Messages.get("menu.view.language"));
        ToggleGroup langGroup = new ToggleGroup();
        for (Messages.Language l : Messages.available(AppSettings.languageDirs())) {
            RadioMenuItem mi = new RadioMenuItem(l.displayName());
            mi.setToggleGroup(langGroup);
            mi.setSelected(l.tag().equals(Messages.current().tag()));
            mi.setOnAction(e -> onLanguageChange.accept(l.tag()));
            language.getItems().add(mi);
        }
        RadioMenuItem dark = new RadioMenuItem(Messages.get("menu.view.dark"));
        RadioMenuItem light = new RadioMenuItem(Messages.get("menu.view.light"));
        ToggleGroup themeGroup = new ToggleGroup();
        dark.setToggleGroup(themeGroup);
        light.setToggleGroup(themeGroup);
        (controller.settings().darkTheme() ? dark : light).setSelected(true);
        dark.setOnAction(e -> onThemeChange.accept(true));
        light.setOnAction(e -> onThemeChange.accept(false));
        view.getItems().addAll(language, new SeparatorMenuItem(), dark, light);

        Menu tools = new Menu(Messages.get("menu.tools"));
        tools.getItems().add(item("menu.tools.ffmpeg", null, this::chooseFfmpeg));

        Menu help = new Menu(Messages.get("menu.help"));
        help.getItems().add(item("menu.help.about", null, this::about));

        return new MenuBar(file, view, tools, help);
    }

    private MenuItem item(String key, String accelerator, Runnable action) {
        MenuItem mi = new MenuItem(Messages.get(key));
        if (accelerator != null) {
            mi.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private void fillRecent(Menu recent) {
        recent.getItems().clear();
        List<String> files = controller.settings().recentProjects();
        if (files.isEmpty()) {
            MenuItem none = new MenuItem(Messages.get("menu.file.recent.none"));
            none.setDisable(true);
            recent.getItems().add(none);
        }
        for (String f : files) {
            MenuItem mi = new MenuItem(f);
            mi.setOnAction(e -> openFile(Path.of(f)));
            recent.getItems().add(mi);
        }
    }

    private ToolBar toolBar() {
        Button add = new Button(Messages.get("toolbar.addFolder"));
        add.getStyleClass().add(Styles.ACCENT);
        add.setOnAction(e -> addFolder());
        Button rescan = new Button(Messages.get("toolbar.rescan"));
        rescan.setOnAction(e -> controller.rescan());
        rescan.disableProperty().bind(controller.scanningProperty());
        Button all = new Button(Messages.get("toolbar.selectAll"));
        all.setOnAction(e -> controller.setAllSelected(true));
        Button none = new Button(Messages.get("toolbar.selectNone"));
        none.setOnAction(e -> controller.setAllSelected(false));
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(18, 18);
        spinner.visibleProperty().bind(controller.scanningProperty());
        return new ToolBar(add, rescan, all, none, spinner);
    }

    private HBox folderBar() {
        Label label = new Label(Messages.get("folders.label"));
        label.getStyleClass().add(Styles.TEXT_MUTED);
        listeners.on(controller.folders(), (ListChangeListener<String>) c -> refreshFolders());
        refreshFolders();
        HBox box = new HBox(8, label, folderChips);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(6, 12, 6, 12));
        HBox.setHgrow(folderChips, Priority.ALWAYS);
        return box;
    }

    private void refreshFolders() {
        folderChips.getChildren().clear();
        if (controller.folders().isEmpty()) {
            Label hint = new Label(Messages.get("folders.none"));
            hint.getStyleClass().add(Styles.TEXT_MUTED);
            folderChips.getChildren().add(hint);
        }
        for (String f : controller.folders()) {
            Button chip = new Button(Messages.get("folders.chip", f));
            chip.getStyleClass().addAll(Styles.SMALL, Styles.BUTTON_OUTLINED);
            chip.setOnAction(e -> controller.removeFolder(f));
            chip.setTooltip(new javafx.scene.control.Tooltip(Messages.get("folders.remove")));
            folderChips.getChildren().add(chip);
        }
    }

    private HBox statusBar() {
        Label status = new Label();
        status.textProperty().bind(controller.statusProperty());
        Label ffmpeg = new Label();
        ffmpeg.textProperty().bind(Bindings.createStringBinding(() -> controller.toolsProperty().get() == null
                ? Messages.get("status.noFfmpeg")
                : Messages.get("status.ffmpegVersion", controller.toolsProperty().get().version()),
                controller.toolsProperty()));
        ffmpeg.getStyleClass().add(Styles.TEXT_MUTED);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, status, spacer, ffmpeg);
        bar.setPadding(new Insets(4, 12, 4, 12));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    // ---------------------------------------------------------------- actions

    private void addFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(Messages.get("toolbar.addFolder"));
        File last = new File(controller.settings().lastFolder());
        if (last.isDirectory()) {
            dc.setInitialDirectory(last);
        }
        File dir = dc.showDialog(stage);
        if (dir != null) {
            controller.addFolder(dir.toPath());
        }
    }

    private void compress() {
        if (!outputBox.commit()) {
            return;
        }
        controller.setStatus("status.planning");
        controller.planCompressAsync(plan -> {
            if (!plan.ok()) {
                controller.setStatus("status.ready");
                error(Messages.get("compress.error.title"), Messages.get(plan.errorKey(), plan.errorArgs().toArray()));
                return;
            }
            controller.startCompressAsync(plan.specs(), msg -> error(Messages.get("compress.error.title"), msg));
        });
    }

    private void sample() {
        if (!outputBox.commit()) {
            compressPanel.showSampleError("");
            return;
        }
        controller.planCompressAsync(plan -> {
            if (!plan.ok()) {
                compressPanel.showSampleError(Messages.get(plan.errorKey(), plan.errorArgs().toArray()));
                return;
            }
            var spec = plan.specs().getFirst();
            controller.sample(spec, r -> compressPanel.showSample(r, spec.totalBytes()),
                    msg -> compressPanel.showSampleError(Messages.get("compress.sample.failed")));
        });
    }

    private void merge() {
        if (!outputBox.commit()) {
            return;
        }
        AppController.MergePlan plan = controller.planMerge();
        if (!plan.ok()) {
            error(Messages.get("merge.error.title"), Messages.get(plan.errorKey(), plan.errorArgs().toArray()));
            return;
        }
        try {
            controller.startMerge(plan.specs());
            controller.setStatus("status.mergeQueued", plan.specs().size());
        } catch (IOException e) {
            error(Messages.get("merge.error.title"), Messages.get("error.io", e.getMessage()));
        }
    }

    private void newProject() {
        if (busy() || !confirmDiscardUntitledJobs()) {
            return;
        }
        controller.newProject();
    }

    /** Paused jobs of an untitled project would be lost when switching; offer to cancel or save first. */
    private boolean confirmDiscardUntitledJobs() {
        if (!controller.switchingLosesJobs()) {
            return true;
        }
        Alert a = new Alert(Alert.AlertType.WARNING);
        a.initOwner(stage);
        a.setTitle(Messages.get("switch.title"));
        a.setHeaderText(Messages.get("switch.header"));
        a.setContentText(Messages.get("switch.text"));
        ButtonType saveFirst = new ButtonType(Messages.get("menu.file.saveAs"), ButtonBar.ButtonData.OK_DONE);
        ButtonType discard = new ButtonType(Messages.get("switch.discard"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(Messages.get("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(saveFirst, discard, cancel);
        ButtonType choice = a.showAndWait().orElse(cancel);
        if (choice == saveFirst) {
            saveAs();
            return !controller.untitledProperty().get();
        }
        return choice == discard;
    }

    private void openProject() {
        if (busy() || !confirmDiscardUntitledJobs()) {
            return;
        }
        FileChooser fc = projectChooser();
        File f = fc.showOpenDialog(stage);
        if (f != null) {
            load(f.toPath());
        }
    }

    /** Opens a recent project. */
    private void openFile(Path file) {
        if (busy() || !confirmDiscardUntitledJobs()) {
            return;
        }
        load(file);
    }

    private void load(Path file) {
        try {
            showRecovery(controller.open(file));
        } catch (IOException e) {
            error(Messages.get("menu.file.open"), Messages.get("status.openFailed", file.getFileName(), e.getMessage()));
        }
    }


    private void save() {
        if (controller.untitledProperty().get()) {
            saveAs();
        } else {
            controller.saveNow();
            controller.setStatus("status.saved");
        }
    }

    private void saveAs() {
        FileChooser fc = projectChooser();
        fc.setInitialFileName(Messages.get("project.defaultName") + "." + ProjectStore.EXTENSION);
        File f = fc.showSaveDialog(stage);
        if (f != null) {
            try {
                controller.saveAs(f.toPath());
                controller.setStatus("status.saved");
            } catch (IOException e) {
                error(Messages.get("menu.file.saveAs"), Messages.get("status.saveFailed", e.getMessage()));
            }
        }
    }

    private FileChooser projectChooser() {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(Messages.get("project.filter"),
                "*." + ProjectStore.EXTENSION));
        File last = new File(controller.settings().lastFolder());
        if (last.isDirectory()) {
            fc.setInitialDirectory(last);
        }
        return fc;
    }

    private boolean busy() {
        if (controller.isQueueRunning()) {
            error(Messages.get("error.busy.title"), Messages.get("error.busy"));
            return true;
        }
        return false;
    }

    public void chooseFfmpeg() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(Messages.get("ffmpeg.choose"));
        File dir = dc.showDialog(stage);
        if (dir != null && !controller.useFfmpegDir(dir.toPath())) {
            error(Messages.get("ffmpeg.choose"), Messages.get("ffmpeg.notInDir", dir));
        } else if (dir != null) {
            controller.rescan();
        }
    }

    private void about() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(stage);
        a.setTitle(Messages.get("menu.help.about"));
        a.setHeaderText(ClipForge.NAME + " " + ClipForge.version());
        a.setContentText(Messages.get("about.text"));
        a.showAndWait();
    }

    // ---------------------------------------------------------------- dialogs

    public void showFfmpegMissing() {
        Alert a = new Alert(Alert.AlertType.WARNING);
        a.initOwner(stage);
        a.setTitle(Messages.get("ffmpeg.missing.title"));
        a.setHeaderText(Messages.get("ffmpeg.missing.header"));
        a.setContentText(Messages.get("ffmpeg.missing.text"));
        ButtonType choose = new ButtonType(Messages.get("ffmpeg.choose"), ButtonBar.ButtonData.OK_DONE);
        a.getButtonTypes().setAll(choose, new ButtonType(Messages.get("common.later"), ButtonBar.ButtonData.CANCEL_CLOSE));
        a.showAndWait().filter(b -> b == choose).ifPresent(b -> chooseFfmpeg());
    }

    /** Asks how to continue with interrupted jobs. */
    public void showRecovery(List<RecoveryService.Issue> issues) {
        List<RecoveryService.Issue> actionable = issues.stream()
                .filter(i -> i.kind() != RecoveryService.Kind.OUTPUT_MISSING).toList();
        if (actionable.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (RecoveryService.Issue i : actionable) {
            sb.append("• ").append(Path.of(i.job().output()).getFileName());
            if (i.kind() == RecoveryService.Kind.SOURCE_CHANGED) {
                sb.append("  — ").append(Messages.get(i.job().getCompress() != null
                        ? "recovery.sourceChangedRestart" : "recovery.sourceChanged", i.changedSources().size()));
            }
            sb.append('\n');
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.initOwner(stage);
        a.setTitle(Messages.get("recovery.title"));
        a.setHeaderText(Messages.get("recovery.header", actionable.size()));
        TextArea details = new TextArea(sb.toString());
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(Math.min(8, actionable.size() + 1));
        a.getDialogPane().setContent(new VBox(8, new Label(Messages.get("recovery.text")), details));
        ButtonType resume = new ButtonType(Messages.get("recovery.resume"), ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType(Messages.get("recovery.later"), ButtonBar.ButtonData.OTHER);
        ButtonType discard = new ButtonType(Messages.get("recovery.discard"), ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(resume, later, discard);
        ButtonType choice = a.showAndWait().orElse(later);
        if (choice == resume) {
            controller.resumeInterrupted(actionable, true);
        } else if (choice == discard) {
            controller.cancelJobs(actionable);
        } else {
            controller.resumeInterrupted(actionable, false);
        }
    }

    /**
     * Handles a window close request while jobs run.
     *
     * @return {@code true} if the window may close now
     */
    public boolean confirmClose() {
        if (!controller.isQueueRunning()) {
            controller.saveNow();
            return true;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.initOwner(stage);
        a.setTitle(Messages.get("close.title"));
        a.setHeaderText(Messages.get("close.header"));
        a.setContentText(Messages.get("close.text"));
        ButtonType pauseExit = new ButtonType(Messages.get("close.pauseExit"), ButtonBar.ButtonData.OK_DONE);
        ButtonType stopExit = new ButtonType(Messages.get("close.stopExit"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(Messages.get("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        a.getButtonTypes().setAll(pauseExit, stopExit, cancel);
        ButtonType choice = a.showAndWait().orElse(cancel);
        if (choice == cancel) {
            return false;
        }
        if (choice == pauseExit) {
            controller.pauseQueue();
        } else {
            controller.stopQueueNow();
        }
        controller.setStatus("close.waiting");
        root.setDisable(true);
        Thread.ofVirtual().start(() -> {
            try {
                controller.awaitQueueIdle(java.time.Duration.ofHours(6));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Platform.runLater(() -> {
                controller.saveNow();
                Platform.exit();
            });
        });
        return false;
    }

    public void error(String title, String text) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.initOwner(stage);
        a.setTitle(title);
        a.setHeaderText(title);
        a.setContentText(text);
        a.showAndWait();
    }
}
