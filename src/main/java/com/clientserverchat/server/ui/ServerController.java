/*
 * File: ServerController.java
 * Vai trò: Controller cho giao diện server.
 * Mục đích: Nối nút bấm trên ServerView với ChatServer và UserRegistry, chạy tác vụ mạng ngoài UI thread.
 * Phương thức chính:
 * - khoiDongServer(): đọc IP/cổng và mở ChatServer.
 * - dungServer(): dừng server đang chạy.
 * - ghiNhatKy()/baoLoi(): cập nhật nhật ký và lỗi trên giao diện.
 * - close(): dọn tài nguyên khi thoát app.
 */
package com.clientserverchat.server.ui;

import com.clientserverchat.server.core.ChatServer;
import com.clientserverchat.server.core.UserRegistry;
import javafx.application.Platform;
import java.util.concurrent.*;

/** Coordinates server lifecycle outside the JavaFX application thread and displays user activity. */
public final class ServerController implements AutoCloseable {
    private final UserRegistry registry;
    private final ServerView view;
    private final ExecutorService actions = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("server-actions").factory());
    private volatile ChatServer server;
    private volatile boolean disposed;

    /** Gắn sự kiện UI, nhận snapshot tài khoản từ registry và khởi tạo trạng thái nút. */
    public ServerController(UserRegistry registry, ServerView view) {
        this.registry = registry;
        this.view = view;
        registry.khiDuLieuThayDoi(snapshot -> Platform.runLater(() -> view.showUsers(snapshot)));
        view.start.setOnAction(event -> khoiDongServer());
        view.stop.setOnAction(event -> dungServer());
        view.showRunning(false, false);
    }

    /** Khởi động ChatServer ở luồng nền sau khi kiểm tra IP/cổng nhập trên giao diện. */
    private void khoiDongServer() {
        try {
            String ip = view.ip.getText().trim();
            if (ip.isEmpty()) throw new IllegalArgumentException("Vui lòng nhập IP server");
            int port = Integer.parseInt(view.port.getText().trim());
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Cổng phải nằm trong 1–65535");
            view.notice.setText("");
            view.showRunning(false, true);
            actions.execute(() -> {
                try {
                    ChatServer next = new ChatServer(ip, port, registry, this::ghiNhatKy);
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
                    Platform.runLater(() -> { view.showRunning(false, false); baoLoi(e); });
                }
            });
        } catch (NumberFormatException e) { baoLoi(new IllegalArgumentException("Cổng TCP phải là số từ 1 đến 65535")); }
        catch (IllegalArgumentException e) { baoLoi(e); }
    }

    /** Dừng ChatServer ở luồng nền và đưa giao diện về trạng thái đã dừng. */
    private void dungServer() {
        view.showRunning(true, true);
        actions.execute(() -> {
            ChatServer current = server;
            if (current != null) current.close();
            server = null;
            ghiNhatKy("Server đã dừng. Các client đã được ngắt kết nối.");
            Platform.runLater(() -> {
                view.showRunning(false, false);
                view.notice.setText("");
            });
        });
    }

    /** Ghi một dòng nhật ký lên TextArea của server từ bất kỳ luồng nào. */
    private void ghiNhatKy(String text) { Platform.runLater(() -> view.appendLog(text)); }

    /** Hiển thị lỗi lên vùng thông báo và đồng thời ghi vào nhật ký. */
    private void baoLoi(Throwable error) { view.notice.setText(error.getMessage()); view.appendLog("LỖI: " + error.getMessage()); }

    /** Đóng controller: dừng server nếu đang chạy và hủy executor nền. */
    @Override public synchronized void close() {
        disposed = true;
        ChatServer current = server;
        if (current != null) current.close();
        actions.shutdownNow();
    }
}
