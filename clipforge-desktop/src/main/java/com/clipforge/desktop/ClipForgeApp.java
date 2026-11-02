package com.clipforge.desktop;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.project.RecoveryService;
import com.clipforge.desktop.view.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.List;
import java.util.Locale;

public class ClipForgeApp extends Application {

    private AppSettings settings;
    private AppController controller;
    private Stage stage;
    private MainView view;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        settings = AppSettings.user();
        Messages.use(initialLanguage(), AppSettings.languageDirs());
        applyTheme(settings.darkTheme());

        controller = new AppController(settings);
        boolean ffmpeg = controller.initFfmpeg();
        showView();
        stage.setWidth(1280);
        stage.setHeight(820);
        stage.setOnCloseRequest(e -> {
            if (!view.confirmClose()) {
                e.consume();
            }
        });
        stage.show();

        List<RecoveryService.Issue> issues = controller.openLastProject();
        Platform.runLater(() -> {
            if (!ffmpeg) {
                view.showFfmpegMissing();
            }
            view.showRecovery(issues);
        });
    }

    private String initialLanguage() {
        String saved = settings.language();
        if (!saved.isBlank()) {
            return saved;
        }
        String system = Locale.getDefault().getLanguage();
        boolean known = Messages.available(AppSettings.languageDirs()).stream()
                .anyMatch(l -> l.tag().equals(system));
        return known ? system : Messages.DEFAULT_TAG;
    }

    /** (Re)creates the UI, e.g. after the language changed; application state stays in the controller. */
    private void showView() {
        view = new MainView(controller, stage, getHostServices(), this::changeLanguage, this::changeTheme);
        Scene scene = new Scene(view.root());
        scene.getStylesheets().add(ClipForgeApp.class.getResource("app.css").toExternalForm());
        stage.setScene(scene);
    }

    private void changeLanguage(String tag) {
        settings.setLanguage(tag);
        Messages.use(tag, AppSettings.languageDirs());
        controller.retranslate();
        showView();
    }

    private void changeTheme(boolean dark) {
        settings.setDarkTheme(dark);
        applyTheme(dark);
    }

    private static void applyTheme(boolean dark) {
        Application.setUserAgentStylesheet(dark ? new PrimerDark().getUserAgentStylesheet()
                : new PrimerLight().getUserAgentStylesheet());
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.saveNow();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
