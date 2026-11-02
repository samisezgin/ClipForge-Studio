package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.compress.CompressOptions;
import com.clipforge.core.compress.CompressSampler;
import com.clipforge.core.compress.EncoderProfile;
import com.clipforge.core.compress.QualityBand;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.util.Formats;
import com.clipforge.desktop.AppController;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Compression settings: source, encoder, quality, segments, audio, a measured sample and the start button. */
public final class CompressPanel extends VBox {

    /** Segment length choices in seconds; 0 = automatic. */
    private static final List<Integer> SEGMENT_CHOICES = List.of(0, 30, 60, 120, 300, 600);

    private final AppController controller;
    private final Consumer<Boolean> onStart;
    private final RadioButton fromSelection = new RadioButton(Messages.get("compress.source.selection"));
    private final RadioButton fromFile = new RadioButton(Messages.get("compress.source.file"));
    private final TextField file = new TextField();
    private final ComboBox<EncoderProfile> encoder = new ComboBox<>();
    private final Slider quality = new Slider();
    private final Label qualityValue = new Label();
    private final ComboBox<Integer> segment = new ComboBox<>(FXCollections.observableArrayList(SEGMENT_CHOICES));
    private final Spinner<Integer> parallel = new Spinner<>(1, 4, 1);
    private final javafx.scene.control.CheckBox overlay = new javafx.scene.control.CheckBox(Messages.get("compress.overlay"));
    private final Label sampleResult = new Label();
    private final Button sampleButton = new Button(Messages.get("compress.sample"));
    private final Button startButton = new Button(Messages.get("compress.start"));
    private boolean loading;
    private boolean sampling;
    private boolean optionsUsable;
    /** All quality ranges of the chosen encoder; the one the slider is in is highlighted. */
    private final VBox bands = new VBox(2);
    private final Label[] bandLabels = new Label[QualityBand.values().length];
    private final Label summary = new Label();
    private final Label encoderNote = new Label();

    /**
     * @param onStart called with {@code true} to start compressing, {@code false} to encode a sample
     */
    public CompressPanel(AppController controller, ListenerBag listeners, Consumer<Boolean> onStart) {
        this.controller = controller;
        this.onStart = onStart;
        setSpacing(10);
        setPadding(new Insets(12, 0, 0, 0));

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

        encoder.setMaxWidth(Double.MAX_VALUE);
        encoder.setCellFactory(l -> new EncoderCell(null));
        encoder.setButtonCell(new EncoderCell(encoder)); // shows the prompt (detecting / none) when empty
        encoder.valueProperty().addListener((o, a, p) -> {
            if (p != null && !loading) {
                loading = true;
                quality.setMin(p.minQuality());
                quality.setMax(p.maxQuality());
                quality.setValue(p.defaultQuality());
                parallel.getValueFactory().setValue(p.defaultConcurrency());
                loading = false;
                updateBands();
                saveOptions();
            }
        });
        quality.setMajorTickUnit(5);
        quality.setBlockIncrement(1);
        quality.setSnapToTicks(false);
        quality.valueProperty().addListener((o, a, b) -> {
            double rounded = Math.rint(b.doubleValue());
            if (rounded != b.doubleValue()) {
                quality.setValue(rounded); // re-enters this listener once with the whole number
                return;
            }
            qualityValue.setText(Messages.get("compress.quality.value", Math.round(rounded)));
            updateBands();
            saveOptions();
        });
        quality.setTooltip(new Tooltip(Messages.get("compress.quality.tip")));
        HBox qualityRow = new HBox(8, quality, qualityValue);
        HBox.setHgrow(quality, Priority.ALWAYS);
        for (int i = 0; i < bandLabels.length; i++) {
            Label l = new Label();
            l.getStyleClass().addAll("quality-band", Styles.TEXT_SMALL);
            l.setWrapText(true);
            l.setMaxWidth(Double.MAX_VALUE);
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
            sampleButton.setDisable(true);
            onStart.accept(false);
        });
        startButton.getStyleClass().addAll(Styles.ACCENT, Styles.LARGE);
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> onStart.accept(true));

        Label note = new Label(Messages.get("compress.note"));
        note.setWrapText(true);
        note.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        summary.setWrapText(true);
        summary.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        encoderNote.setWrapText(true);
        encoderNote.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.WARNING);
        getChildren().addAll(fromSelection, fromFile, fileRow, summary, new Separator(),
                new Label(Messages.get("compress.encoder")), encoder, encoderNote,
                new Label(Messages.get("compress.quality")), qualityRow, bands, grid, overlay,
                new Separator(),
                sampleButton, sampleResult, startButton, note);

        listeners.on(controller.encodersProperty(), (o, a, b) -> load());
        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load());
        listeners.on(controller.selectionVersionProperty(), (o, a, b) -> updateSummary());
        listeners.on(controller.sessions(), c -> updateSummary());
        listeners.on(controller.scanningProperty(), (o, a, b) -> updateButtons());
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
            boolean usable = o != null;
            optionsUsable = usable;
            updateButtons();
            boolean replaced = controller.savedEncoderUnavailable();
            encoderNote.setText(replaced ? Messages.get("compress.encoder.replaced") : "");
            encoderNote.setVisible(replaced);
            encoderNote.setManaged(replaced);
            updateSummary();
            fromFile.setSelected(controller.compressFromFile());
            fromSelection.setSelected(!controller.compressFromFile());
            file.setText(controller.compressFile() == null ? "" : controller.compressFile());
            CompressOptions saved = controller.compressOptionsSaved();
            overlay.setSelected(saved != null && saved.overlayTime());
            if (!usable) {
                return;
            }
            encoder.setValue(o.profile());
            quality.setMin(o.profile().minQuality());
            quality.setMax(o.profile().maxQuality());
            quality.setValue(o.quality());
            qualityValue.setText(Messages.get("compress.quality.value", o.quality()));
            segment.setValue(SEGMENT_CHOICES.contains(o.segmentSeconds()) ? o.segmentSeconds() : 0);
            parallel.getValueFactory().setValue(o.concurrency());

        } finally {
            loading = false;
            updateBands();
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
        controller.setCompressOptions(new CompressOptions(encoder.getValue(), (int) Math.round(quality.getValue()),
                segment.getValue() == null ? 0 : segment.getValue(), parallel.getValue(),
                AudioMode.KEEP, com.clipforge.core.audio.MusicSettings.DEFAULT, overlay.isSelected()));
        // audio comes from the shared audio panel when the job is planned
        sampleResult.setText("");
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
        sampleResult.setText("");
        updateSummary();
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

    /** What will be compressed: the chosen file, or the selection grouped by the merge mode. */
    private void updateSummary() {
        updateButtons();
        if (fromFile.isSelected()) {
            String f = file.getText().trim();
            String name;
            try {
                name = f.isEmpty() ? null : String.valueOf(Path.of(f).getFileName());
            } catch (InvalidPathException e) {
                name = f;
            }
            summary.setText(name == null ? Messages.get("error.noCompressFile")
                    : Messages.get("compress.summary.file", name));
            return;
        }
        var sel = controller.selectedClips();
        long bytes = sel.stream().mapToLong(c -> c.size()).sum();
        String mode = Messages.get(controller.mergeMode() == com.clipforge.core.merge.MergeMode.ALL_IN_ONE
                ? "merge.mode.allInOne" : "merge.mode.perSession");
        summary.setText(Messages.get("compress.summary.selection", sel.size(), Formats.size(bytes), mode));
    }

    /** Shows the measured sample and the extrapolated result. */
    /** Compressing the selection waits for the scan: the list is still filling in and sessions may grow. */
    private void updateButtons() {
        boolean blocked = !optionsUsable || (!fromFile.isSelected() && controller.scanningProperty().get());
        startButton.setDisable(blocked);
        sampleButton.setDisable(blocked || sampling);
    }

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
                setText(Messages.get("encoder." + p.name().toLowerCase()));
            }
        }
    }
}
