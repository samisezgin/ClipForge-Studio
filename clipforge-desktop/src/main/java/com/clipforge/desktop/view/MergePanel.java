package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.merge.MergeMode;
import com.clipforge.core.merge.MergeOptions;
import com.clipforge.core.model.Clip;
import com.clipforge.core.util.Formats;
import com.clipforge.desktop.AppController;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Merge mode, output folder and options, with a summary of the current selection. */
public final class MergePanel extends VBox {

    private final AppController controller;
    private final Label summary = new Label();
    private final TextField outputDir = new TextField();
    private final Button mergeButton = new Button(Messages.get("merge.start"));

    public MergePanel(AppController controller, Runnable onMerge) {
        this.controller = controller;
        setSpacing(10);
        setPadding(new Insets(12));
        setMinWidth(280);
        setPrefWidth(320);
        getStyleClass().add("side-panel");

        Label title = new Label(Messages.get("merge.title"));
        title.getStyleClass().add(Styles.TITLE_4);

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton perSession = new RadioButton(Messages.get("merge.mode.perSession"));
        perSession.setToggleGroup(modeGroup);
        perSession.setUserData(MergeMode.PER_SESSION);
        perSession.setTooltip(new Tooltip(Messages.get("merge.mode.perSession.tip")));
        RadioButton allInOne = new RadioButton(Messages.get("merge.mode.allInOne"));
        allInOne.setToggleGroup(modeGroup);
        allInOne.setUserData(MergeMode.ALL_IN_ONE);
        allInOne.setTooltip(new Tooltip(Messages.get("merge.mode.allInOne.tip")));
        modeGroup.selectToggle(controller.mergeMode() == MergeMode.ALL_IN_ONE ? allInOne : perSession);
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) {
                controller.setMergeMode((MergeMode) b.getUserData());
            }
        });

        Label outLabel = new Label(Messages.get("merge.outputDir"));
        outputDir.setText(controller.outputDir() == null ? "" : controller.outputDir());
        outputDir.setPromptText(Messages.get("merge.outputDir.prompt"));
        outputDir.textProperty().addListener((o, a, b) -> controller.setOutputDir(b.isBlank() ? null : Path.of(b)));
        Button browse = new Button(Messages.get("common.browse"));
        browse.setOnAction(e -> chooseOutputDir());
        HBox outRow = new HBox(6, outputDir, browse);
        HBox.setHgrow(outputDir, Priority.ALWAYS);

        MergeOptions opts = controller.mergeOptions();
        CheckBox faststart = new CheckBox(Messages.get("merge.faststart"));
        faststart.setSelected(opts.faststart());
        faststart.setTooltip(new Tooltip(Messages.get("merge.faststart.tip")));
        faststart.selectedProperty().addListener((o, a, b) -> controller.setMergeOptions(new MergeOptions(b)));

        summary.setWrapText(true);
        summary.getStyleClass().add(Styles.TEXT_MUTED);

        mergeButton.getStyleClass().addAll(Styles.ACCENT, Styles.LARGE);
        mergeButton.setMaxWidth(Double.MAX_VALUE);
        mergeButton.setOnAction(e -> onMerge.run());

        Label lossless = new Label(Messages.get("merge.lossless"));
        lossless.setWrapText(true);
        lossless.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        getChildren().addAll(title, perSession, allInOne, new Separator(), outLabel, outRow,
                faststart, new Separator(), summary, mergeButton, lossless);

        controller.projectVersionProperty().addListener((o, a, b) -> {
            modeGroup.selectToggle(controller.mergeMode() == MergeMode.ALL_IN_ONE ? allInOne : perSession);
            outputDir.setText(controller.outputDir() == null ? "" : controller.outputDir());
            faststart.setSelected(controller.mergeOptions().faststart());
        });
        controller.selectionVersionProperty().addListener((o, a, b) -> updateSummary());
        controller.sessions().addListener((javafx.collections.ListChangeListener<com.clipforge.core.model.Session>) c -> updateSummary());
        updateSummary();
    }

    private void chooseOutputDir() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(Messages.get("merge.outputDir"));
        String current = outputDir.getText();
        File initial = current.isBlank() ? new File(controller.settings().lastFolder()) : new File(current);
        if (initial.isDirectory()) {
            dc.setInitialDirectory(initial);
        }
        File dir = dc.showDialog(getScene().getWindow());
        if (dir != null) {
            outputDir.setText(dir.getAbsolutePath());
        }
    }

    private void updateSummary() {
        List<Clip> sel = controller.selectedClips();
        Duration d = sel.stream().map(Clip::duration).reduce(Duration.ZERO, Duration::plus);
        long bytes = sel.stream().mapToLong(Clip::size).sum();
        summary.setText(Messages.get("merge.summary", sel.size(), Formats.duration(d), Formats.size(bytes)));
        mergeButton.setDisable(sel.isEmpty());
    }
}
