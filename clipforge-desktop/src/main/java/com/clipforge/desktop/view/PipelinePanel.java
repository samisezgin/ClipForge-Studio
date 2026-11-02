package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.model.Clip;
import com.clipforge.core.util.Formats;
import com.clipforge.desktop.AppController;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * What one run does: any combination of merge, speed-up and compression, applied to the selected clips or to one
 * video file, started with a single button. The tabs below hold each step's settings.
 */
public final class PipelinePanel extends VBox {

    private final AppController controller;
    private final CheckBox merge = new CheckBox(Messages.get("pipeline.merge"));
    private final CheckBox speed = new CheckBox(Messages.get("pipeline.speed"));
    private final CheckBox compress = new CheckBox(Messages.get("pipeline.compress"));
    private final RadioButton fromSelection = new RadioButton(Messages.get("compress.source.selection"));
    private final RadioButton fromFile = new RadioButton(Messages.get("compress.source.file"));
    private final TextField file = new TextField();
    private final Label summary = new Label();
    private final Label note = new Label();
    private final Button start = new Button(Messages.get("pipeline.start"));
    private boolean loading;

    public PipelinePanel(AppController controller, ListenerBag listeners, Runnable onStart) {
        this.controller = controller;
        setSpacing(8);
        setPadding(new Insets(10));
        getStyleClass().add("pipeline-panel");

        Label title = new Label(Messages.get("pipeline.title"));
        title.getStyleClass().add(Styles.TITLE_4);
        merge.setTooltip(new Tooltip(Messages.get("pipeline.merge.tip")));
        speed.setTooltip(new Tooltip(Messages.get("pipeline.speed.tip")));
        compress.setTooltip(new Tooltip(Messages.get("pipeline.compress.tip")));
        for (CheckBox c : List.of(merge, speed, compress)) {
            c.selectedProperty().addListener((o, a, b) -> saveSteps());
        }
        FlowPane steps = new FlowPane(16, 6, merge, speed, compress);

        ToggleGroup source = new ToggleGroup();
        fromSelection.setToggleGroup(source);
        fromFile.setToggleGroup(source);
        fromSelection.setTooltip(new Tooltip(Messages.get("compress.source.selection.tip")));
        file.setPromptText(Messages.get("compress.source.file.prompt"));
        Button browse = new Button(Messages.get("common.browse"));
        browse.setOnAction(e -> chooseFile());
        HBox fileRow = new HBox(6, file, browse);
        HBox.setHgrow(file, Priority.ALWAYS);
        fileRow.disableProperty().bind(fromFile.selectedProperty().not());
        source.selectedToggleProperty().addListener((o, a, b) -> saveSource());
        file.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                saveSource();
            }
        });
        file.setOnAction(e -> saveSource());

        summary.setWrapText(true);
        summary.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        note.setWrapText(true);
        note.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.WARNING);
        start.getStyleClass().addAll(Styles.ACCENT, Styles.LARGE);
        start.setMaxWidth(Double.MAX_VALUE);
        start.setOnAction(e -> onStart.run());

        getChildren().addAll(title, steps, fromSelection, fromFile, fileRow, summary, note, start);

        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load());
        listeners.on(controller.selectionVersionProperty(), (o, a, b) -> update());
        listeners.on(controller.sessions(), c -> update());
        listeners.on(controller.scanningProperty(), (o, a, b) -> update());
        listeners.on(controller.toolsProperty(), (o, a, b) -> update());
        listeners.on(controller.encodersProperty(), (o, a, b) -> update());
        listeners.on(controller.pipelineVersionProperty(), (o, a, b) -> update());
        load();
    }

    private void load() {
        loading = true;
        try {
            merge.setSelected(controller.stepMerge());
            speed.setSelected(controller.stepSpeed());
            compress.setSelected(controller.stepCompress());
            fromFile.setSelected(controller.compressFromFile());
            fromSelection.setSelected(!controller.compressFromFile());
            file.setText(controller.compressFile() == null ? "" : controller.compressFile());
        } finally {
            loading = false;
        }
        update();
    }

    private void saveSteps() {
        if (!loading) {
            controller.setSteps(merge.isSelected(), speed.isSelected(), compress.isSelected());
        }
        update();
    }

    private void saveSource() {
        if (loading) {
            return;
        }
        Path p = null;
        try {
            String t = file.getText().trim();
            p = t.isEmpty() ? null : Path.of(t);
        } catch (InvalidPathException ignored) {
            // invalid text: keep the source mode, the plan reports the missing file
        }
        controller.setCompressSource(fromFile.isSelected(), p);
        update();
    }

    private void chooseFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle(Messages.get("compress.source.file"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(Messages.get("compress.filter.video"),
                "*.mp4", "*.mov", "*.mkv", "*.m4v", "*.avi", "*.ts", "*.mts", "*.m2ts", "*.webm",
                "*.MP4", "*.MOV", "*.MKV"));
        File last = new File(controller.settings().lastFolder());
        if (last.isDirectory()) {
            fc.setInitialDirectory(last);
        }
        File f = fc.showOpenDialog(getScene().getWindow());
        if (f != null) {
            file.setText(f.getAbsolutePath());
            fromFile.setSelected(true);
            saveSource();
        }
    }

    /** Describes what the run will do and enables the button when it can start. */
    private void update() {
        boolean onFile = fromFile.isSelected();
        // merging joins clips; a single file has nothing to join
        merge.setDisable(onFile);
        boolean doMerge = merge.isSelected() && !onFile;
        boolean doSpeed = speed.isSelected();
        boolean doCompress = compress.isSelected();

        List<String> parts = new ArrayList<>();
        if (doMerge) {
            parts.add(Messages.get("pipeline.step.merge"));
        }
        if (doSpeed) {
            parts.add(Messages.get("pipeline.step.speed", com.clipforge.core.compress.CompressOptions.formatSpeed(
                    controller.speedFactor())));
        }
        if (doCompress) {
            parts.add(Messages.get("pipeline.step.compress"));
        }
        String what;
        if (onFile) {
            String f = file.getText().trim();
            String name;
            try {
                name = f.isEmpty() ? null : String.valueOf(Path.of(f).getFileName());
            } catch (InvalidPathException e) {
                name = f;
            }
            what = name == null ? Messages.get("error.noCompressFile") : name;
        } else {
            List<Clip> sel = controller.selectedClips();
            Duration d = sel.stream().map(Clip::duration).reduce(Duration.ZERO, Duration::plus);
            long bytes = sel.stream().mapToLong(Clip::size).sum();
            what = Messages.get("merge.summary", sel.size(), Formats.duration(d), Formats.size(bytes));
            if (!doMerge && (doSpeed || doCompress) && sel.size() > 1) {
                what += " · " + Messages.get("pipeline.eachClip");
            }
        }
        boolean nothing = parts.isEmpty();
        summary.setText(nothing ? Messages.get("pipeline.nothing")
                : what + " → " + String.join(" → ", parts));

        String warn = null;
        if (controller.toolsProperty().get() == null) {
            warn = Messages.get("error.noFfmpeg");
        } else if (doSpeed && !doCompress) {
            warn = Messages.get("pipeline.note.speedOnly");
        }
        note.setText(warn == null ? "" : warn);
        note.setVisible(warn != null);
        note.setManaged(warn != null);

        boolean encodes = doSpeed || doCompress;
        boolean blocked = nothing
                || controller.toolsProperty().get() == null
                || (encodes && controller.compressOptions() == null)
                || (!onFile && (controller.selectedClips().isEmpty() || controller.scanningProperty().get()));
        start.setDisable(blocked);
    }
}
