/*
 * File: ServerApp.java
 * Vai trò: Giao diện ứng dụng server JavaFX.
 * Mục đích: Tạo cửa sổ quản lý server, nạp CSS, ghép ServerView với ServerController.
 * Phương thức chính:
 * - start(): dựng giao diện server và mở file dữ liệu tài khoản.
 * - stop(): đóng server khi tắt cửa sổ.
 * - main(): chạy ứng dụng server.
 */
package com.clientserverchat.server;

import com.clientserverchat.server.core.UserRegistry;
import com.clientserverchat.server.ui.ServerController;
import com.clientserverchat.server.ui.ServerView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.net.URL;

public final class ServerApp extends Application {
    private ServerController controller;

    /** Khởi tạo màn hình quản lý server và bộ lưu tài khoản. */
    @Override public void start(Stage stage) {
        try {
            ServerView view = new ServerView();
            controller = new ServerController(new UserRegistry(Path.of("data/server/accounts.txt")), view);
            Scene scene = new Scene(view, 1120, 800);
            URL css = ServerApp.class.getResource("/styles/chat.css");
            if (css != null) scene.getStylesheets().add(css.toExternalForm());
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

    /** Đóng controller để dừng server và ngắt các client khi thoát ứng dụng. */
    @Override public void stop() { if (controller != null) controller.close(); }

    /** Điểm chạy trực tiếp khi mở ServerApp từ IDE. */
    public static void main(String[] args) { launch(args); }
}
