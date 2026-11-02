package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.merge.MergeMode;
import com.clipforge.core.merge.MergeOptions;
import com.clipforge.desktop.AppController;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;

/**
 * Merge settings: how selected clips are grouped into outputs, and the layout of a lossless merge. Merging is
 * started from the {@link PipelinePanel}, alone or together with speed-up and compression.
 */
public final class MergePanel extends TabContent {

    public MergePanel(AppController controller, ListenerBag listeners) {
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

        Label lossless = new Label(Messages.get("merge.lossless"));
        lossless.setWrapText(true);
        lossless.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        Label combined = new Label(Messages.get("merge.combined"));
        combined.setWrapText(true);
        combined.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        getChildren().addAll(perSession, allInOne, faststart, new Separator(), lossless, combined);

        Runnable load = () -> {
            modeGroup.selectToggle(controller.mergeMode() == MergeMode.ALL_IN_ONE ? allInOne : perSession);
            faststart.setSelected(controller.mergeOptions().faststart());
        };
        load.run();
        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load.run());
    }
}
