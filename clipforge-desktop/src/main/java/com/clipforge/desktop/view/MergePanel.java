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
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.util.List;

/**
 * Lossless merge: mode and options, with a summary of the current selection. The mode also decides how selected
 * clips are grouped for compression.
 */
public final class MergePanel extends VBox {

    private final AppController controller;
    private final Label summary = new Label();
    private final Button mergeButton = new Button(Messages.get("merge.start"));

    public MergePanel(AppController controller, ListenerBag listeners, Runnable onMerge) {
        this.controller = controller;
        setSpacing(10);
        setPadding(new Insets(12, 0, 0, 0));

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton perSession = new RadioButton(Messages.get("merge.mode.perSession"));
        perSession.setToggleGroup(modeGroup);
        perSession.setUserData(MergeMode.PER_SESSION);
        perSession.setTooltip(new Tooltip(Messages.get("merge.mode.perSession.tip")));
        RadioButton allInOne = new RadioButton(Messages.get("merge.mode.allInOne"));
        allInOne.setToggleGroup(modeGroup);
        allInOne.setUserData(MergeMode.ALL_IN_ONE);
        allInOne.setTooltip(new Tooltip(Messages.get("merge.mode.allInOne.tip")));
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) {
                controller.setMergeMode((MergeMode) b.getUserData());
            }
        });

        CheckBox faststart = new CheckBox(Messages.get("merge.faststart"));
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

        getChildren().addAll(perSession, allInOne, faststart, new Separator(), summary, mergeButton, lossless);

        Runnable load = () -> {
            modeGroup.selectToggle(controller.mergeMode() == MergeMode.ALL_IN_ONE ? allInOne : perSession);
            faststart.setSelected(controller.mergeOptions().faststart());
        };
        load.run();
        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load.run());
        listeners.on(controller.selectionVersionProperty(), (o, a, b) -> updateSummary());
        listeners.on(controller.sessions(), c -> updateSummary());
        listeners.on(controller.toolsProperty(), (o, a, b) -> updateSummary());
        updateSummary();
    }

    private void updateSummary() {
        List<Clip> sel = controller.selectedClips();
        Duration d = sel.stream().map(Clip::duration).reduce(Duration.ZERO, Duration::plus);
        long bytes = sel.stream().mapToLong(Clip::size).sum();
        boolean noFfmpeg = controller.toolsProperty().get() == null;
        summary.setText(noFfmpeg ? Messages.get("error.noFfmpeg")
                : Messages.get("merge.summary", sel.size(), Formats.duration(d), Formats.size(bytes)));
        mergeButton.setDisable(sel.isEmpty() || noFfmpeg);
    }
}
