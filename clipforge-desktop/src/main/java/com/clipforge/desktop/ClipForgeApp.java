package com.clipforge.desktop;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.clipforge.core.ClipForge;
import com.clipforge.core.i18n.Messages;
import com.clipforge.core.project.RecoveryService;
import com.clipforge.desktop.view.MainView;
import com.clipforge.desktop.view.TextFit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class ClipForgeApp extends Application {

    private AppSettings settings;
    private AppController controller;
    private Stage stage;
    private MainView view;

    /** Rotating log files in ~/.clipforge/logs (the packaged app has no console). */
    static void setUpLogging() {
        try {
            Path dir = AppSettings.homeDir().resolve("logs");
            Files.createDirectories(dir);
            FileHandler file = new FileHandler(
                    dir.resolve("clipforge-%g.log").toString(), 5_000_000, 5, true);
            file.setEncoding("UTF-8");
            file.setFormatter(new SimpleFormatter());
            Logger root = Logger.getLogger("");
            root.addHandler(file);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            System.err.println("Cannot write log files: " + e);
        }
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> Logger.getLogger("ClipForge")
                .log(Level.SEVERE, "Uncaught exception in " + t.getName(), e));
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        setUpLogging();
        Logger.getLogger("ClipForge").info("Starting ClipForge Studio "
                + ClipForge.displayVersion());
        settings = AppSettings.user();
        Messages.use(initialLanguage(), AppSettings.languageDirs());
        applyTheme(settings.darkTheme());

        controller = new AppController(settings);
        boolean ffmpeg = controller.initFfmpeg();
        showView();
        stage.setWidth(1280);
        stage.setHeight(820);
        // window, taskbar and Alt+Tab icon; dialogs owned by the stage inherit it
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            var url = ClipForgeApp.class.getResource("icons/icon-" + size + ".png");
            if (url != null) {
                stage.getIcons().add(new Image(url.toExternalForm()));
            }
        }
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
        if (view != null) {
            view.dispose();
        }
        view = new MainView(controller, stage, getHostServices(), this::changeLanguage, this::changeTheme);
        Scene scene = new Scene(view.root());
        scene.getStylesheets().add(ClipForgeApp.class.getResource("app.css").toExternalForm());
        TextFit.installTruncationTooltips(scene);
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
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
