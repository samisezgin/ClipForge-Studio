package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.audio.AudioMode;
import com.clipforge.core.audio.MusicSettings;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.util.Formats;
import com.clipforge.desktop.AppController;
import javafx.beans.binding.Bindings;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Audio for merging and compression: keep, remove, or background music with playlist, loop + crossfade,
 * optional fade-out (on/off + seconds), volume and an optional quiet mix of the original sound.
 */
public final class AudioPanel extends TitledPane {

    private final AppController controller;
    private final RadioButton keep = new RadioButton(Messages.get("audio.keep"));
    private final RadioButton remove = new RadioButton(Messages.get("audio.remove"));
    private final RadioButton music = new RadioButton(Messages.get("audio.music"));
    private final ListView<String> tracks = new ListView<>();
    private final Label playlistLength = new Label();
    private final CheckBox loop = new CheckBox(Messages.get("audio.loop"));
    private final Slider crossfade = new Slider(0, MusicSettings.MAX_CROSSFADE, 1);
    private final Label crossfadeValue = new Label();
    private final CheckBox fadeOut = new CheckBox(Messages.get("audio.fadeOut"));
    private final Spinner<Double> fadeSeconds = new Spinner<>();
    private final Slider volume = new Slider(0, 200, 100);
    private final Label volumeValue = new Label();
    private final CheckBox mixOriginal = new CheckBox(Messages.get("audio.mixOriginal"));
    private final Slider originalVolume = new Slider(0, 100, 20);
    private final Label originalValue = new Label();
    private boolean loading;

    public AudioPanel(AppController controller, ListenerBag listeners) {
        this.controller = controller;
        setText(Messages.get("audio.title"));
        setAnimated(false);

        ToggleGroup mode = new ToggleGroup();
        keep.setToggleGroup(mode);
        remove.setToggleGroup(mode);
        music.setToggleGroup(mode);
        remove.setTooltip(new Tooltip(Messages.get("audio.remove.tip")));
        mode.selectedToggleProperty().addListener((o, a, b) -> {
            save();
            updateLength();
        });

        tracks.setPrefHeight(110);
        tracks.setPlaceholder(new Label(Messages.get("audio.tracks.empty")));
        tracks.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : Path.of(item).getFileName().toString());
                setTooltip(empty || item == null ? null : new Tooltip(item));
            }
        });
        Button add = small("audio.tracks.add", e -> addTracks());
        tracks.setPrefHeight(84);
        Button del = small("audio.tracks.remove", e -> {
            int i = tracks.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                tracks.getItems().remove(i);
                save();
                updateLength();
            }
        });
        Button up = small("audio.tracks.up", e -> move(-1));
        Button down = small("audio.tracks.down", e -> move(1));
        var selected = tracks.getSelectionModel().selectedIndexProperty();
        del.disableProperty().bind(selected.lessThan(0));
        up.disableProperty().bind(selected.lessThan(1));
        down.disableProperty().bind(selected.lessThan(0).or(Bindings.createBooleanBinding(
                () -> selected.get() >= tracks.getItems().size() - 1, selected, tracks.getItems())));
        HBox trackButtons = new HBox(4, add, del, up, down);
        trackButtons.setAlignment(Pos.CENTER_LEFT);
        playlistLength.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);

        loop.setTooltip(new Tooltip(Messages.get("audio.loop.tip")));
        loop.selectedProperty().addListener((o, a, b) -> save());
        crossfade.setTooltip(new Tooltip(Messages.get("audio.crossfade.tip")));
        crossfade.valueProperty().addListener((o, a, b) -> {
            double r = Math.round(b.doubleValue() * 10) / 10.0;
            if (r != b.doubleValue()) {
                crossfade.setValue(r);
                return;
            }
            crossfadeValue.setText(Messages.get("audio.seconds", r));
            save();
        });

        fadeSeconds.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(
                MusicSettings.MIN_FADE, MusicSettings.MAX_FADE, 3, 0.5));
        fadeSeconds.setEditable(true);
        fadeSeconds.setPrefWidth(90);
        fadeSeconds.disableProperty().bind(fadeOut.selectedProperty().not());
        fadeSeconds.valueProperty().addListener((o, a, b) -> save());
        // a Spinner only commits typed text on Enter: also commit when the field loses focus
        fadeSeconds.getEditor().focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commitPending();
            }
        });
        fadeOut.selectedProperty().addListener((o, a, b) -> save());
        HBox fadeRow = new HBox(8, fadeOut, fadeSeconds, new Label(Messages.get("audio.secondsUnit")));
        fadeRow.setAlignment(Pos.CENTER_LEFT);

        volume.valueProperty().addListener((o, a, b) -> {
            double r = Math.rint(b.doubleValue());
            if (r != b.doubleValue()) {
                volume.setValue(r);
                return;
            }
            volumeValue.setText(Messages.get("audio.percent", (int) r));
            save();
        });
        mixOriginal.setTooltip(new Tooltip(Messages.get("audio.mixOriginal.tip")));
        mixOriginal.selectedProperty().addListener((o, a, b) -> save());
        originalVolume.disableProperty().bind(mixOriginal.selectedProperty().not());
        originalVolume.valueProperty().addListener((o, a, b) -> {
            double r = Math.rint(b.doubleValue());
            if (r != b.doubleValue()) {
                originalVolume.setValue(r);
                return;
            }
            originalValue.setText(Messages.get("audio.percent", (int) r));
            save();
        });

        TitledPane more = new TitledPane(Messages.get("audio.more"), new VBox(8,
                row(new Label(Messages.get("audio.crossfade")), crossfade, crossfadeValue),
                row(new Label(Messages.get("audio.volume")), volume, volumeValue),
                mixOriginal, row(new Label(Messages.get("audio.originalVolume")), originalVolume, originalValue)));
        more.setExpanded(false);
        more.setAnimated(false);
        VBox musicBox = new VBox(8, tracks, trackButtons, playlistLength, loop, fadeRow, more);
        musicBox.disableProperty().bind(music.selectedProperty().not());
        musicBox.managedProperty().bind(music.selectedProperty());
        musicBox.visibleProperty().bind(music.selectedProperty());

        VBox content = new VBox(8, keep, remove, music, musicBox);
        setContent(content);

        load();
        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load());
        listeners.on(controller.toolsProperty(), (o, a, b) -> updateLength());
    }

    private static HBox row(Label label, Slider slider, Label value) {
        HBox h = new HBox(8, label, slider, value);
        h.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        value.setMinWidth(48);
        return h;
    }

    private static Button small(String key, EventHandler<ActionEvent> action) {
        Button b = new Button(Messages.get(key));
        b.getStyleClass().add(Styles.SMALL);
        b.setOnAction(action);
        return b;
    }

    private void load() {
        loading = true;
        try {
            AudioMode m = controller.audioMode();
            keep.setSelected(m == AudioMode.KEEP);
            remove.setSelected(m == AudioMode.REMOVE);
            music.setSelected(m == AudioMode.MUSIC);
            MusicSettings s = controller.music();
            tracks.getItems().setAll(s.tracks());
            loop.setSelected(s.loop());
            crossfade.setValue(s.crossfadeSeconds());
            crossfadeValue.setText(Messages.get("audio.seconds", s.crossfadeSeconds()));
            fadeOut.setSelected(s.fadeOut());
            fadeSeconds.getValueFactory().setValue(s.fadeOutSeconds());
            volume.setValue(Math.round(s.volume() * 100));
            volumeValue.setText(Messages.get("audio.percent", Math.round(s.volume() * 100)));
            mixOriginal.setSelected(s.mixOriginal());
            originalVolume.setValue(Math.round(s.originalVolume() * 100));
            originalValue.setText(Messages.get("audio.percent", Math.round(s.originalVolume() * 100)));
            setExpanded(m != AudioMode.KEEP);
        } finally {
            loading = false;
        }
        updateLength();
    }

    /** Commits a fade-out length that was typed but not confirmed with Enter; call before starting a job. */
    public void commitPending() {
        String text = fadeSeconds.getEditor().getText();
        try {
            double v = Double.parseDouble(text.trim().replace(',', '.'));
            v = Math.max(MusicSettings.MIN_FADE, Math.min(MusicSettings.MAX_FADE, v));
            if (!Double.valueOf(v).equals(fadeSeconds.getValue())) {
                fadeSeconds.getValueFactory().setValue(v);
            }
            fadeSeconds.getEditor().setText(fadeSeconds.getValueFactory().getConverter().toString(v));
        } catch (NumberFormatException e) {
            // not a number: show the last valid value again
            fadeSeconds.getEditor().setText(fadeSeconds.getValueFactory().getConverter().toString(fadeSeconds.getValue()));
        }
    }

    private void save() {
        if (loading) {
            return;
        }
        AudioMode m = remove.isSelected() ? AudioMode.REMOVE : music.isSelected() ? AudioMode.MUSIC : AudioMode.KEEP;
        Double fade = fadeSeconds.getValue();
        MusicSettings s = new MusicSettings(new ArrayList<>(tracks.getItems()), loop.isSelected(),
                crossfade.getValue(), fadeOut.isSelected(), fade == null ? 3 : fade, volume.getValue() / 100.0,
                mixOriginal.isSelected(), originalVolume.getValue() / 100.0);
        controller.setAudio(m, s);
    }

    private void addTracks() {
        FileChooser fc = new FileChooser();
        fc.setTitle(Messages.get("audio.tracks.add"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(Messages.get("audio.filter"),
                "*.mp3", "*.m4a", "*.aac", "*.flac", "*.wav", "*.ogg", "*.opus", "*.MP3", "*.M4A", "*.FLAC", "*.WAV"));
        List<File> files = fc.showOpenMultipleDialog(getScene().getWindow());
        if (files != null && !files.isEmpty()) {
            files.forEach(f -> tracks.getItems().add(f.getAbsolutePath()));
            save();
            updateLength();
        }
    }

    private void move(int delta) {
        int i = tracks.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= tracks.getItems().size()) {
            return;
        }
        String item = tracks.getItems().remove(i);
        tracks.getItems().add(j, item);
        tracks.getSelectionModel().select(j);
        save();
    }

    private long measureGeneration;

    private void updateLength() {
        if (tracks.getItems().isEmpty() || !music.isSelected()) {
            playlistLength.setText("");
            return;
        }
        long generation = ++measureGeneration;
        int count = tracks.getItems().size();
        controller.measureMusic(seconds -> {
            if (generation != measureGeneration) {
                return; // an older playlist: a newer measurement is on its way
            }
            playlistLength.setText(seconds < 0 ? Messages.get("audio.tracks.unreadable")
                    : Messages.get("audio.tracks.length", count,
                    Formats.duration(Duration.ofMillis(Math.round(seconds * 1000)))));
        });
    }
}
