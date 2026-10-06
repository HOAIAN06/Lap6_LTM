package com.clientserverchat.server;

import com.clientserverchat.server.core.UserRegistry;
import com.clientserverchat.server.ui.ServerController;
import com.clientserverchat.server.ui.ServerView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import java.nio.file.Path;

public final class ServerApp extends Application {
    private ServerController controller;
    @Override public void start(Stage stage) {
        try {
            ServerView view = new ServerView();
            controller = new ServerController(new UserRegistry(Path.of("data/server/accounts.properties")), view);
            Scene scene = new Scene(view, 1120, 800);
            scene.getStylesheets().add(ServerApp.class.getResource("/styles/chat.css").toExternalForm());
            stage.setTitle("Quản lý server");
            stage.setMinWidth(1000);
            stage.setMinHeight(700);
            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Không mở được server: " + e.getMessage()).showAndWait();
            javafx.application.Platform.exit();
        }
    }
    @Override public void stop() { if (controller != null) controller.close(); }
    public static void main(String[] args) { launch(args); }
}
