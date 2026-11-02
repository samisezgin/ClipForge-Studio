package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.compress.CompressOptions;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.model.Clip;
import com.clipforge.core.util.Formats;
import com.clipforge.desktop.AppController;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

import java.time.Duration;

/** Speed-up factor (applied when "Speed up" is ticked in the {@link PipelinePanel}). */
public final class SpeedPanel extends VBox {

    /** The slider covers the common range; larger factors (up to 100x) can be typed. */
    private static final double SLIDER_MAX = 10;

    private final AppController controller;
    private final SliderBox speed;
    private final Label summary = new Label();
    private boolean loading;

    public SpeedPanel(AppController controller, ListenerBag listeners) {
        this.controller = controller;
        setSpacing(10);
        setPadding(new Insets(12, 0, 0, 0));

        speed = new SliderBox(CompressOptions.MIN_SPEED, CompressOptions.MAX_SPEED, SLIDER_MAX, 0.25,
                controller.speedFactor());
        Tooltip tip = new Tooltip(Messages.get("speed.factor.tip"));
        speed.slider().setTooltip(tip);
        speed.box().setTooltip(tip);
        speed.slider().setMajorTickUnit(1);
        speed.slider().setMinorTickCount(3);
        speed.valueProperty().addListener((o, a, b) -> {
            if (!loading) {
                controller.setSpeedFactor(b.doubleValue());
            }
            updateSummary();
        });

        summary.setWrapText(true);
        summary.getStyleClass().add(Styles.TEXT_MUTED);
        Label audio = note("speed.note.audio");
        Label encode = note("speed.note.encode");
        getChildren().addAll(new Label(Messages.get("speed.factor")), speed, summary, audio, encode);

        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load());
        listeners.on(controller.selectionVersionProperty(), (o, a, b) -> updateSummary());
        listeners.on(controller.sessions(), c -> updateSummary());
        listeners.on(controller.pipelineVersionProperty(), (o, a, b) -> updateSummary());
        load();
    }

    private static Label note(String key) {
        Label l = new Label(Messages.get(key));
        l.setWrapText(true);
        l.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        return l;
    }

    private void load() {
        loading = true;
        try {
            speed.setValue(controller.speedFactor());
        } finally {
            loading = false;
        }
        updateSummary();
    }

    private void updateSummary() {
        double f = speed.getValue();
        if (controller.compressFromFile()) {
            summary.setText(Messages.get("speed.summary.file", CompressOptions.formatSpeed(f)));
            return;
        }
        Duration d = controller.selectedClips().stream().map(Clip::duration).reduce(Duration.ZERO, Duration::plus);
        summary.setText(Messages.get("speed.summary", Formats.duration(d),
                Formats.duration(Duration.ofMillis(Math.round(d.toMillis() / f))), CompressOptions.formatSpeed(f)));
    }
}
