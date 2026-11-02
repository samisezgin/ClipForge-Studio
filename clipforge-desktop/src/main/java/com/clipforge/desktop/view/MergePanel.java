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
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

/**
 * Merge settings: how selected clips are grouped into outputs, and the layout of a lossless merge. Merging is
 * started from the {@link PipelinePanel}, alone or together with speed-up and compression.
 * <p>
 * The free mode joins any selected videos in the order set here; videos of different formats are re-encoded to the
 * first one's resolution and frame rate.
 */
public final class MergePanel extends TabContent {

    private final AppController controller;
    private final ListView<Clip> order = new ListView<>();
    private final VBox freeBox = new VBox(6);
    private final Label formatNote = new Label();
    private final Button up = new Button("↑");
    private final Button down = new Button("↓");

    public MergePanel(AppController controller, ListenerBag listeners) {
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
        RadioButton free = new RadioButton(Messages.get("merge.mode.free"));
        free.setToggleGroup(modeGroup);
        free.setUserData(MergeMode.FREE);
        free.setTooltip(new Tooltip(Messages.get("merge.mode.free.tip")));
        modeGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) {
                controller.setMergeMode((MergeMode) b.getUserData());
                updateFree();
            }
        });

        // free mode: the order of the joined videos
        order.setPrefHeight(170);
        order.setMinHeight(Region.USE_PREF_SIZE);
        order.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(Clip c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText((getIndex() + 1) + ". " + c.fileName() + "  ·  " + Formats.duration(c.duration())
                            + (c.media() == null ? "" : "  ·  " + c.media().resolution()));
                    setTooltip(new Tooltip(c.path().toString()));
                }
            }
        });
        order.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updateButtons());
        up.setTooltip(new Tooltip(Messages.get("merge.order.up")));
        down.setTooltip(new Tooltip(Messages.get("merge.order.down")));
        up.setOnAction(e -> move(-1));
        down.setOnAction(e -> move(1));
        Button byTime = new Button(Messages.get("merge.order.byTime"));
        Button byName = new Button(Messages.get("merge.order.byName"));
        byTime.setOnAction(e -> controller.sortOrder(false));
        byName.setOnAction(e -> controller.sortOrder(true));
        for (Button b : List.of(up, down, byTime, byName)) {
            b.getStyleClass().add(Styles.SMALL);
        }
        HBox buttons = new HBox(6, up, down, byTime, byName);
        formatNote.setWrapText(true);
        formatNote.getStyleClass().addAll(Styles.TEXT_SMALL);
        Label orderTitle = new Label(Messages.get("merge.order.title"));
        freeBox.getChildren().addAll(orderTitle, order, buttons, formatNote);

        CheckBox faststart = new CheckBox(Messages.get("merge.faststart"));
        faststart.setTooltip(new Tooltip(Messages.get("merge.faststart.tip")));
        faststart.selectedProperty().addListener((o, a, b) -> controller.setMergeOptions(new MergeOptions(b)));

        Label lossless = new Label(Messages.get("merge.lossless"));
        lossless.setWrapText(true);
        lossless.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        Label combined = new Label(Messages.get("merge.combined"));
        combined.setWrapText(true);
        combined.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        getChildren().addAll(perSession, allInOne, free, freeBox, faststart, new Separator(), lossless, combined);

        Runnable load = () -> {
            MergeMode m = controller.mergeMode();
            modeGroup.selectToggle(m == MergeMode.ALL_IN_ONE ? allInOne : m == MergeMode.FREE ? free : perSession);
            faststart.setSelected(controller.mergeOptions().faststart());
            updateFree();
        };
        load.run();
        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load.run());
        // the mode can change elsewhere (project load, tests): keep the radio buttons in step
        listeners.on(controller.selectionVersionProperty(), (o, a, b) -> load.run());
        listeners.on(controller.sessions(), c -> updateFree());
    }

    private void move(int delta) {
        Clip c = order.getSelectionModel().getSelectedItem();
        if (c != null) {
            controller.moveInOrder(c, delta);
            order.getSelectionModel().select(c); // the list was rebuilt: keep the moved clip selected
        }
    }

    /** Shows the order list in free mode and whether the videos can be joined losslessly. */
    private void updateFree() {
        boolean on = controller.mergeMode() == MergeMode.FREE;
        freeBox.setVisible(on);
        freeBox.setManaged(on);
        if (!on) {
            return;
        }
        Clip keep = order.getSelectionModel().getSelectedItem();
        List<Clip> clips = controller.orderedSelection();
        order.getItems().setAll(clips);
        if (keep != null && clips.contains(keep)) {
            order.getSelectionModel().select(keep);
        }
        if (clips.size() < 2) {
            formatNote.setText(Messages.get("merge.free.few"));
            formatNote.getStyleClass().remove(Styles.WARNING);
        } else if (controller.freeMergeNeedsEncoding()) {
            Clip first = clips.getFirst();
            formatNote.setText(Messages.get("merge.free.reencode", first.media().resolution(),
                    String.format(Locale.ROOT, "%.3f", first.media().frameRate())
                            .replaceAll("\\.?0+$", "")));
            if (!formatNote.getStyleClass().contains(Styles.WARNING)) {
                formatNote.getStyleClass().add(Styles.WARNING);
            }
        } else {
            formatNote.setText(Messages.get("merge.free.lossless"));
            formatNote.getStyleClass().remove(Styles.WARNING);
        }
        updateButtons();
    }

    private void updateButtons() {
        int i = order.getSelectionModel().getSelectedIndex();
        up.setDisable(i <= 0);
        down.setDisable(i < 0 || i >= order.getItems().size() - 1);
    }
}
