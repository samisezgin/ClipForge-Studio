package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.project.SourceDeletion;
import com.clipforge.core.util.Formats;
import com.clipforge.core.util.RecycleBin;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Asked when starting with "Delete source files" ticked: delete during or after the work, and Recycle Bin (default)
 * or permanently. Cancel or closing the window starts nothing.
 */
public final class DeleteSourcesDialog {

    private DeleteSourcesDialog() {
    }

    /**
     * @param files         the sources the jobs would delete
     * @param duringAllowed deleting during the work is possible (jobs that re-encode; not a lossless merge)
     * @return the choice, or empty if the user cancelled
     */
    static Optional<SourceDeletion> ask(Window owner, List<Path> files, boolean duringAllowed) {
        return create(owner, files, duringAllowed).showAndWait();
    }

    /** The dialog, not shown yet (tests show it without blocking). */
    public static Dialog<SourceDeletion> create(Window owner, List<Path> files, boolean duringAllowed) {
        long bytes = 0;
        for (Path f : files) {
            try {
                bytes += Files.size(f);
            } catch (IOException ignored) {
                // vanished meanwhile: counts as 0
            }
        }
        Dialog<SourceDeletion> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle(Messages.get("delete.title"));
        d.setHeaderText(Messages.get("delete.header", files.size(), Formats.size(bytes)));

        ListView<String> list = new ListView<>();
        files.forEach(f -> list.getItems().add(f.toString()));
        list.setPrefHeight(Math.min(6, Math.max(2, files.size())) * 26 + 4);
        list.setMinHeight(Region.USE_PREF_SIZE); // the VBox must not squeeze the list under the options

        ToggleGroup when = new ToggleGroup();
        RadioButton after = new RadioButton(Messages.get("delete.after"));
        RadioButton during = new RadioButton(Messages.get("delete.during"));
        after.setToggleGroup(when);
        during.setToggleGroup(when);
        after.setSelected(true);
        during.setDisable(!duringAllowed);
        Label afterNote = note(Messages.get("delete.after.note"));
        Label duringNote = note(Messages.get(duringAllowed ? "delete.during.note" : "delete.during.unavailable"));

        CheckBox permanent = new CheckBox(Messages.get("delete.permanent"));
        Label warning = new Label(Messages.get("delete.permanent.warning"));
        warning.setWrapText(true);
        warning.getStyleClass().add("delete-warning");
        warning.visibleProperty().bind(permanent.selectedProperty());
        warning.managedProperty().bind(permanent.selectedProperty());

        Label kept = note(Messages.get("delete.kept.note"));
        VBox box = new VBox(8, list, after, afterNote, during, duringNote, new Separator(), permanent, warning, kept);
        boolean noBin = !RecycleBin.isSupported();
        if (noBin) {
            // without a Recycle Bin only a permanent deletion is possible; the user has to choose it knowingly
            Label nb = new Label(Messages.get("delete.noRecycleBin"));
            nb.setWrapText(true);
            nb.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.WARNING);
            box.getChildren().add(nb);
        }
        box.setPadding(new Insets(4, 0, 0, 0));
        box.setPrefWidth(560);
        TextFit.fixWrapping(box);
        d.getDialogPane().setContent(box);
        // a dialog has its own scene: it needs the app style sheet for the warning box
        var css = DeleteSourcesDialog.class.getResource("/com/clipforge/desktop/app.css");
        if (css != null) {
            d.getDialogPane().getStylesheets().add(css.toExternalForm());
        }

        ButtonType start = new ButtonType(Messages.get("delete.start"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(Messages.get("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().setAll(start, cancel);
        Button startButton = (Button) d.getDialogPane().lookupButton(start);
        // its text changes with the choice: no uniform button width, never cut off
        ButtonBar.setButtonUniformSize(startButton, false);
        startButton.setMinWidth(Region.USE_PREF_SIZE);
        Runnable style = () -> {
            startButton.getStyleClass().removeAll(Styles.DANGER, Styles.ACCENT);
            startButton.getStyleClass().add(permanent.isSelected() ? Styles.DANGER : Styles.ACCENT);
            startButton.setText(Messages.get(permanent.isSelected() ? "delete.start.permanent" : "delete.start"));
            startButton.setDisable(noBin && !permanent.isSelected());
        };
        permanent.selectedProperty().addListener((o, a, b) -> {
            style.run();
            d.getDialogPane().getScene().getWindow().sizeToScene(); // room for the warning
        });
        style.run();
        // the safe choice has the focus: Enter must never start a permanent deletion by accident
        ((Button) d.getDialogPane().lookupButton(cancel)).setDefaultButton(false);
        startButton.setDefaultButton(false);

        d.setResultConverter(b -> b == start ? new SourceDeletion(
                during.isSelected() ? SourceDeletion.When.DURING : SourceDeletion.When.AFTER,
                permanent.isSelected()) : null);
        return d;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
        l.setPadding(new Insets(0, 0, 0, 24));
        return l;
    }
}
