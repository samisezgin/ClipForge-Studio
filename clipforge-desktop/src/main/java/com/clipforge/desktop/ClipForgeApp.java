package com.clipforge.desktop;

import atlantafx.base.theme.PrimerDark;
import com.clipforge.core.ClipForge;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class ClipForgeApp extends Application {

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        stage.setTitle(ClipForge.NAME);
        stage.setScene(new Scene(new StackPane(new Label(ClipForge.NAME + " " + ClipForge.version())), 960, 640));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
