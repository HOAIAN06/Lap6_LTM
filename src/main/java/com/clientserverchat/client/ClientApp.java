/*
 * File: ClientApp.java
 * Vai trò: Giao diện ứng dụng client JavaFX.
 * Mục đích: Tạo cửa sổ client, nạp CSS, ghép ClientView với ClientController.
 * Phương thức chính:
 * - start(): dựng giao diện client.
 * - stop(): đóng tài nguyên mạng khi tắt cửa sổ.
 * - main(): chạy ứng dụng client.
 */
package com.clientserverchat.client;

import com.clientserverchat.client.core.ChatClient;
import com.clientserverchat.client.ui.ClientController;
import com.clientserverchat.client.ui.ClientView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class ClientApp extends Application {
    private ClientController controller;

    /** Khởi tạo màn hình client, controller và stylesheet cho giao diện chat. */
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

    /** Đóng controller để ngắt TCP/multicast khi cửa sổ client thoát. */
    @Override public void stop() { if (controller != null) controller.close(); }

    /** Điểm chạy trực tiếp khi mở ClientApp từ IDE. */
    public static void main(String[] args) { launch(args); }
}
