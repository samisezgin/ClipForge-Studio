package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.i18n.Messages;
import com.clipforge.desktop.AppController;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Output folder shared by merging and compression; validated and committed on Enter, focus loss or Browse. */
public final class OutputFolderBox extends VBox {

    private final AppController controller;
    private final TextField field = new TextField();
    private final Label error = new Label();

    public OutputFolderBox(AppController controller, ListenerBag listeners) {
        this.controller = controller;
        setSpacing(6);
        Label label = new Label(Messages.get("merge.outputDir"));
        field.setPromptText(Messages.get("merge.outputDir.prompt"));
        field.setOnAction(e -> commit());
        field.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commit();
            }
        });
        Button browse = new Button(Messages.get("common.browse"));
        browse.setOnAction(e -> choose());
        HBox row = new HBox(6, field, browse);
        HBox.setHgrow(field, Priority.ALWAYS);
        error.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.DANGER);
        error.setWrapText(true);
        setError(null);
        getChildren().addAll(label, row, error);

        Runnable load = () -> {
            field.setText(controller.outputDir() == null ? "" : controller.outputDir());
            setError(null);
        };
        load.run();
        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load.run());
    }

    /** Commits the typed path; returns {@code false} if it is invalid. */
    public boolean commit() {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            controller.setOutputDir(null);
            setError(null);
            return true;
        }
        try {
            Path p = Path.of(text);
            if (!p.isAbsolute()) {
                setError(Messages.get("merge.outputDir.notAbsolute"));
                return false;
            }
            controller.setOutputDir(p);
            setError(null);
            return true;
        } catch (InvalidPathException e) {
            setError(Messages.get("merge.outputDir.invalid"));
            return false;
        }
    }

    private void setError(String text) {
        boolean show = text != null;
        error.setText(show ? text : "");
        error.setVisible(show);
        error.setManaged(show);
        field.pseudoClassStateChanged(Styles.STATE_DANGER, show);
    }

    private void choose() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(Messages.get("merge.outputDir"));
        File initial = new File(controller.settings().lastFolder());
        try {
            String current = field.getText().trim();
            if (!current.isEmpty() && new File(current).isDirectory()) {
                initial = new File(current);
            }
        } catch (InvalidPathException ignored) {
            // fall back to the last folder
        }
        if (initial.isDirectory()) {
            dc.setInitialDirectory(initial);
        }
        File dir = dc.showDialog(getScene().getWindow());
        if (dir != null) {
            field.setText(dir.getAbsolutePath());
            commit();
        }
    }
}
