package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicSettings;
import com.clipforge.core.compress.CompressOptions;
import com.clipforge.core.compress.CompressSampler;
import com.clipforge.core.compress.EncoderProfile;
import com.clipforge.core.compress.QualityBand;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.util.Formats;
import com.clipforge.desktop.AppController;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compression settings: encoder, quality, segments, date stamp and a measured sample. What is compressed and when
 * is chosen in the {@link PipelinePanel}; these settings are also used by a speed-up without compression, except the
 * quality (that one then keeps close to the source).
 */
public final class CompressPanel extends TabContent {

    /** Segment length choices in seconds; 0 = automatic. */
    private static final List<Integer> SEGMENT_CHOICES = List.of(0, 30, 60, 120, 300, 600);

    private final AppController controller;
    private final ComboBox<EncoderProfile> encoder = new ComboBox<>();
    private final SliderBox quality = new SliderBox(24, 56, 56, 1, 38);
    private final ComboBox<Integer> segment = new ComboBox<>(FXCollections.observableArrayList(SEGMENT_CHOICES));
    private final Spinner<Integer> parallel = new Spinner<>(1, 4, 1);
    private final CheckBox overlay = new CheckBox(Messages.get("compress.overlay"));
    private final Label sampleResult = new Label();
    private final Button sampleButton = new Button(Messages.get("compress.sample"));
    /** All quality ranges of the chosen encoder; the one the value is in is highlighted. */
    private final VBox bands = new VBox(2);
    private final Label[] bandLabels = new Label[QualityBand.values().length];
    private final Label encoderNote = new Label();
    private boolean loading;
    private boolean sampling;
    private boolean optionsUsable;

    /** @param onSample encodes a sample with the current settings */
    public CompressPanel(AppController controller, ListenerBag listeners, Runnable onSample) {
        this.controller = controller;
        setSpacing(10);
        setPadding(new Insets(12, 0, 0, 0));

        encoder.setMaxWidth(Double.MAX_VALUE);
        encoder.setCellFactory(l -> new EncoderCell(null));
        encoder.setButtonCell(new EncoderCell(encoder)); // shows the prompt (detecting / none) when empty
        encoder.valueProperty().addListener((o, a, p) -> {
            if (p != null && !loading) {
                loading = true;
                quality.setRange(p.minQuality(), p.maxQuality(), p.maxQuality());
                quality.setValue(p.defaultQuality());
                parallel.getValueFactory().setValue(p.defaultConcurrency());
                loading = false;
                updateBands();
                saveOptions();
            }
        });
        quality.valueProperty().addListener((o, a, b) -> {
            updateBands();
            saveOptions();
        });
        Tooltip qualityTip = new Tooltip(Messages.get("compress.quality.tip"));
        quality.slider().setTooltip(qualityTip);
        quality.box().setTooltip(qualityTip);
        Label qualityHint = new Label(Messages.get("compress.quality.hint"));
        qualityHint.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        qualityHint.setWrapText(true);
        for (int i = 0; i < bandLabels.length; i++) {
            Label l = new Label();
            l.getStyleClass().addAll("quality-band", Styles.TEXT_SMALL);
            l.setWrapText(true);
            l.setMaxWidth(Double.MAX_VALUE);
            // a preset: clicking a range selects its lowest value (the best quality within it)
            QualityBand band = QualityBand.values()[i];
            l.setCursor(Cursor.HAND);
            l.setOnMouseClicked(e -> {
                EncoderProfile p = encoder.getValue();
                if (p != null) {
                    quality.setValue(p.range(band)[0]);
                }
            });
            bandLabels[i] = l;
            bands.getChildren().add(l);
        }

        segment.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer s) {
                if (s == null || s == 0) {
                    return Messages.get("compress.segment.auto");
                }
                return s < 60 ? Messages.get("compress.segment.seconds", s) : Messages.get("compress.segment.minutes", s / 60);
            }

            @Override
            public Integer fromString(String string) {
                return 0;
            }
        });
        segment.setTooltip(new Tooltip(Messages.get("compress.segment.tip")));
        segment.valueProperty().addListener((o, a, b) -> saveOptions());
        parallel.setTooltip(new Tooltip(Messages.get("compress.parallel.tip")));
        parallel.valueProperty().addListener((o, a, b) -> saveOptions());
        parallel.setPrefWidth(80);
        overlay.setTooltip(new Tooltip(Messages.get("compress.overlay.tip")));
        overlay.selectedProperty().addListener((o, a, b) -> saveOptions());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label(Messages.get("compress.segment")), segment);
        grid.addRow(1, new Label(Messages.get("compress.parallel")), parallel);

        sampleResult.setWrapText(true);
        sampleResult.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        sampleButton.setMaxWidth(Double.MAX_VALUE);
        sampleButton.setTooltip(new Tooltip(Messages.get("compress.sample.tip")));
        sampleButton.setOnAction(e -> {
            sampleResult.setText(Messages.get("compress.sample.running"));
            sampling = true;
            updateButtons();
            onSample.run();
        });

        Label note = new Label(Messages.get("compress.note"));
        note.setWrapText(true);
        note.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        encoderNote.setWrapText(true);
        encoderNote.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.WARNING);
        getChildren().addAll(new Label(Messages.get("compress.encoder")), encoder, encoderNote,
                new Label(Messages.get("compress.quality")), quality, qualityHint, bands, grid, overlay,
                new Separator(), sampleButton, sampleResult, note);

        listeners.on(controller.encodersProperty(), (o, a, b) -> load());
        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load());
        listeners.on(controller.scanningProperty(), (o, a, b) -> updateButtons());
        listeners.on(controller.pipelineVersionProperty(), (o, a, b) -> updateButtons());
        load();
    }

    private void load() {
        loading = true;
        try {
            Set<EncoderProfile> av = controller.encodersProperty().get();
            encoder.getItems().setAll(av == null ? List.of() : List.of(EncoderProfile.values()).stream()
                    .filter(av::contains).toList());
            encoder.setPromptText(Messages.get(av == null ? "compress.encoder.detecting" : "compress.encoder.none"));
            CompressOptions o = controller.compressOptions();
            optionsUsable = o != null;
            boolean replaced = controller.savedEncoderUnavailable();
            encoderNote.setText(replaced ? Messages.get("compress.encoder.replaced") : "");
            encoderNote.setVisible(replaced);
            encoderNote.setManaged(replaced);
            CompressOptions saved = controller.compressOptionsSaved();
            overlay.setSelected(saved != null && saved.overlayTime());
            if (o == null) {
                return;
            }
            encoder.setValue(o.profile());
            quality.setRange(o.profile().minQuality(), o.profile().maxQuality(), o.profile().maxQuality());
            quality.setValue(o.quality());
            segment.setValue(SEGMENT_CHOICES.contains(o.segmentSeconds()) ? o.segmentSeconds() : 0);
            parallel.getValueFactory().setValue(o.concurrency());
        } finally {
            loading = false;
            updateBands();
            updateButtons();
        }
    }

    private void updateBands() {
        EncoderProfile p = encoder.getValue();
        bands.setVisible(p != null);
        bands.setManaged(p != null);
        if (p == null) {
            return;
        }
        QualityBand active = p.band((int) Math.round(quality.getValue()));
        for (QualityBand b : QualityBand.values()) {
            Label l = bandLabels[b.ordinal()];
            int[] r = p.range(b);
            l.setText(Messages.get("compress.band.range", r[0], r[1], Messages.get("compress.band." + b.name())));
            l.setTooltip(new Tooltip(Messages.get("compress.band.pick", r[0])));
            l.getStyleClass().remove("active");
            if (b == active) {
                l.getStyleClass().add("active");
            }
        }
    }

    private void saveOptions() {
        if (loading || encoder.getValue() == null) {
            return;
        }
        // audio comes from the shared audio panel and the speed from the speed tab when the job is planned
        controller.setCompressOptions(new CompressOptions(encoder.getValue(), (int) Math.round(quality.getValue()),
                segment.getValue() == null ? 0 : segment.getValue(), parallel.getValue(),
                AudioMode.KEEP, MusicSettings.DEFAULT, overlay.isSelected()));
        sampleResult.setText("");
    }

    /** The sample waits for the scan when it would use the selection (the list is still filling in). */
    private void updateButtons() {
        boolean blocked = !optionsUsable || (!controller.compressFromFile() && controller.scanningProperty().get());
        sampleButton.setDisable(blocked || sampling);
    }

    /** Shows the measured sample and the extrapolated result. */
    public void showSample(CompressSampler.Result r, long inputBytes) {
        sampling = false;
        updateButtons();
        sampleResult.setText(Messages.get("compress.sample.result", r.bitrateKbps() / 1000.0,
                Formats.size(r.estimatedBytes()), Math.round((1 - r.ratio(inputBytes)) * 100), r.speed(),
                Formats.duration(r.estimatedTime())));
    }

    public void showSampleError(String text) {
        sampling = false;
        updateButtons();
        sampleResult.setText(text);
    }

    private static final class EncoderCell extends ListCell<EncoderProfile> {
        private final ComboBox<EncoderProfile> owner;

        EncoderCell(ComboBox<EncoderProfile> owner) {
            this.owner = owner;
        }

        @Override
        protected void updateItem(EncoderProfile p, boolean empty) {
            super.updateItem(p, empty);
            if (empty || p == null) {
                // a custom button cell hides the ComboBox prompt, so show it here
                setText(owner == null ? null : owner.getPromptText());
            } else {
                setText(Messages.get("encoder." + p.name().toLowerCase(Locale.ROOT)));
            }
        }
    }
}
