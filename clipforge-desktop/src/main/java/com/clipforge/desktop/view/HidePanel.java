package com.clipforge.desktop.view;

import atlantafx.base.theme.Styles;
import com.clipforge.core.i18n.Messages;
import com.clipforge.desktop.AppController;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;

/**
 * Settings of "Hide plates and faces" (applied when the step is ticked in the {@link PipelinePanel}): what to hide,
 * and an honest note on what automatic detection can and cannot do.
 */
public final class HidePanel extends TabContent {

    private final AppController controller;
    private final CheckBox plates = new CheckBox(Messages.get("hide.plates"));
    private final CheckBox faces = new CheckBox(Messages.get("hide.faces"));
    private final Label models = new Label();
    private boolean loading;

    public HidePanel(AppController controller, ListenerBag listeners) {
        this.controller = controller;
        setSpacing(10);
        setPadding(new Insets(12, 0, 0, 0));

        plates.selectedProperty().addListener((o, a, b) -> save());
        faces.selectedProperty().addListener((o, a, b) -> save());
        Label how = note("hide.how");
        Label limits = note("hide.limits");
        limits.getStyleClass().remove(Styles.TEXT_MUTED);
        limits.getStyleClass().add(Styles.WARNING);
        Label speed = note("hide.speed");
        models.setWrapText(true);
        models.getStyleClass().add(Styles.TEXT_SMALL);
        getChildren().addAll(new Label(Messages.get("hide.what")), plates, faces, how, limits, speed, models);

        listeners.on(controller.projectVersionProperty(), (o, a, b) -> load());
        listeners.on(controller.modelsReadyProperty(), (o, a, b) -> updateModels());
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
            plates.setSelected(controller.hidePlates());
            faces.setSelected(controller.hideFaces());
        } finally {
            loading = false;
        }
        updateModels();
    }

    private void save() {
        if (!plates.isSelected() && !faces.isSelected()) {
            // hiding nothing makes no sense: keep at least one
            (plates.isFocused() ? faces : plates).setSelected(true);
            return;
        }
        if (!loading) {
            controller.setHideTargets(plates.isSelected(), faces.isSelected());
        }
    }

    private void updateModels() {
        boolean ready = controller.modelsReadyProperty().get();
        models.setText(Messages.get(ready ? "hide.models.ready" : "hide.models.missing"));
        models.getStyleClass().removeAll(Styles.SUCCESS, Styles.WARNING);
        models.getStyleClass().add(ready ? Styles.SUCCESS : Styles.WARNING);
    }
}
