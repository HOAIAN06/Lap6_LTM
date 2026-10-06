package com.clientserverchat.client;

import com.clientserverchat.client.core.ChatClient;
import com.clientserverchat.client.ui.ClientController;
import com.clientserverchat.client.ui.ClientView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class ClientApp extends Application {
    private ClientController controller;
    @Override public void start(Stage stage) {
        ClientView view = new ClientView();
        controller = new ClientController(new ChatClient(), view);
        Scene scene = new Scene(view, 1140, 760);
        scene.getStylesheets().add(ClientApp.class.getResource("/styles/chat.css").toExternalForm());
        stage.setTitle("Server-Client Chat · Client");
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        stage.setScene(scene);
        stage.show();
    }
    @Override public void stop() { if (controller != null) controller.close(); }
    public static void main(String[] args) { launch(args); }
}
