package com.clientserverchat.server.ui;

import com.clientserverchat.server.core.ChatServer;
import com.clientserverchat.server.core.UserInfo;
import com.clientserverchat.server.core.UserRegistry;
import javafx.application.Platform;
import java.util.*;
import java.util.concurrent.*;

/** Coordinates server lifecycle outside the JavaFX application thread and displays user activity. */
public final class ServerController implements AutoCloseable {
    private final UserRegistry registry;
    private final ServerView view;
    private final ExecutorService actions = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("server-actions").factory());
    private volatile ChatServer server;
    private volatile boolean disposed;
    private List<UserInfo> users = List.of();

    public ServerController(UserRegistry registry, ServerView view) {
        this.registry = registry;
        this.view = view;
        registry.setOnChange(snapshot -> Platform.runLater(() -> { users = snapshot; filter(); }));
        view.search.textProperty().addListener((observable, before, after) -> filter());
        view.onlineOnly.setOnAction(event -> filter());
        view.start.setOnAction(event -> start());
        view.stop.setOnAction(event -> stop());
        view.showRunning(false, false);
    }

    private void filter() {
        String query = view.search.getText().trim().toLowerCase(Locale.ROOT);
        view.showUsers(users, users.stream().filter(user -> (!view.onlineOnly.isSelected() || user.online())
                && (user.username().toLowerCase(Locale.ROOT).contains(query) || user.ip().contains(query))).toList());
    }

    private void start() {
        try {
            String ip = view.ip.getText().trim();
            if (ip.isEmpty()) throw new IllegalArgumentException("Vui lòng nhập IP server");
            int port = Integer.parseInt(view.port.getText().trim());
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Cổng phải nằm trong 1–65535");
            view.notice.setText("");
            view.showRunning(false, true);
            actions.execute(() -> {
                try {
                    ChatServer next = new ChatServer(ip, port, registry, this::log);
                    synchronized (this) {
                        if (disposed) { next.close(); return; }
                        server = next;
                        next.start();
                    }
                    Platform.runLater(() -> {
                        view.showRunning(true, false);
                        view.ip.setText(next.getAddress());
                    });
                } catch (Exception e) {
                    ChatServer failed = server;
                    server = null;
                    if (failed != null) failed.close();
                    Platform.runLater(() -> { view.showRunning(false, false); error(e); });
                }
            });
        } catch (NumberFormatException e) { error(new IllegalArgumentException("Cổng TCP phải là số từ 1 đến 65535")); }
        catch (IllegalArgumentException e) { error(e); }
    }

    private void stop() {
        view.showRunning(true, true);
        actions.execute(() -> {
            ChatServer current = server;
            if (current != null) current.close();
            server = null;
            log("Server đã dừng. Các client đã được ngắt kết nối.");
            Platform.runLater(() -> {
                view.showRunning(false, false);
                view.notice.setText("");
            });
        });
    }

    private void log(String text) { Platform.runLater(() -> view.appendLog(text)); }
    private void error(Throwable error) { view.notice.setText(error.getMessage()); view.appendLog("LỖI: " + error.getMessage()); }

    @Override public synchronized void close() {
        disposed = true;
        ChatServer current = server;
        if (current != null) current.close();
        actions.shutdownNow();
    }
}
