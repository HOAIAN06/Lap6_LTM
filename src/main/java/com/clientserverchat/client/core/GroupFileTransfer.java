/*
 * File: GroupFileTransfer.java
 * Vai trò: Hỗ trợ gửi file trong phòng multicast.
 * Mục đích: Multicast chỉ gửi thông báo FILE_OFFER nhỏ; nội dung file thật được tải qua TCP trực tiếp giữa client.
 * Phương thức chính:
 * - taoThongBaoChiaSe(): tạo token file để phát qua multicast.
 * - nhanThongBaoChiaSe(): đọc FILE_OFFER và bắt đầu tải file.
 * - phucVuTaiTep()/taiTep(): server tạm gửi file và client nhận file.
 */
package com.clientserverchat.client.core;

import com.clientserverchat.common.Protocol;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Multicast advertises a short-lived file token; TCP transfers the actual bytes. */
final class GroupFileTransfer implements AutoCloseable {
    /** Thông tin file đang chia sẻ: đường dẫn, kích thước, thời điểm hết hạn token. */
    private record Shared(Path path, long size, long expires) {}
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Shared> shared = new ConcurrentHashMap<>();
    private final Set<String> received = ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final String username;
    private final Consumer<ChatMessage> onMessage;
    private final Consumer<String> onError;
    private volatile boolean closed;

    /** Mở ServerSocket tạm để các client khác tải file nhóm từ máy này. */
    GroupFileTransfer(String username, Consumer<ChatMessage> onMessage, Consumer<String> onError) throws IOException {
        this.username = username;
        this.onMessage = onMessage;
        this.onError = onError;
        listener = new ServerSocket(0);
        workers.execute(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept();
                    sockets.add(socket);
                    if (closed) socket.close();
                    else workers.execute(() -> phucVuTaiTep(socket));
                } catch (IOException | RejectedExecutionException e) {
                    if (!closed) onError.accept("Không nhận được yêu cầu tải tệp.");
                }
            }
        });
    }

    /** Tạo gói FILE_OFFER chứa token, cổng TCP tạm, kích thước và tên file. */
    String taoThongBaoChiaSe(File file) throws IOException {
        String name = Protocol.validFilename(file.getName());
        if (!file.isFile() || file.length() > Protocol.MAX_FILE_BYTES) throw new IOException("Chọn tệp tối đa 20 MiB.");
        shared.values().removeIf(value -> value.expires < System.currentTimeMillis());
        if (shared.size() >= 32) throw new IOException("Có quá nhiều tệp đang chia sẻ. Hãy thử lại sau.");
        String token = UUID.randomUUID().toString();
        long size = file.length();
        shared.put(token, new Shared(file.toPath(), size, System.currentTimeMillis() + 600_000));
        return "FILE_OFFER|" + username + "|" + token + "|" + listener.getLocalPort() + "|" + size + "|"
                + Base64.getEncoder().encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Phục vụ một client khác tải file bằng token đã phát qua multicast. */
    private void phucVuTaiTep(Socket socket) {
        try (socket) {
            socket.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            Shared file = shared.get(in.readUTF());
            if (file == null || file.expires < System.currentTimeMillis() || Files.size(file.path) != file.size) return;
            out.writeLong(file.size);
            try (InputStream source = Files.newInputStream(file.path)) { source.transferTo(out); }
            out.flush();
        } catch (IOException ignored) {
        } finally { sockets.remove(socket); }
    }

    /** Nhận FILE_OFFER từ multicast, kiểm tra dữ liệu và tạo tác vụ tải file. */
    void nhanThongBaoChiaSe(String payload, InetAddress senderAddress) {
        try {
            String[] parts = payload.split("\\|", 6);
            if (parts.length != 6 || parts[1].equals(username)) return;
            String sender = Protocol.validName(parts[1]);
            String token = UUID.fromString(parts[2]).toString();
            int port = Integer.parseInt(parts[3]);
            long size = Long.parseLong(parts[4]);
            String name = Protocol.validFilename(new String(Base64.getDecoder().decode(parts[5]), java.nio.charset.StandardCharsets.UTF_8));
            if (port < 1 || port > 65535 || size < 0 || size > Protocol.MAX_FILE_BYTES) return;
            if (received.size() > 1000) received.clear();
            if (!received.add(senderAddress.getHostAddress() + token)) return;
            workers.execute(() -> taiTep(senderAddress, port, token, sender, name, size));
        } catch (IOException | IllegalArgumentException | RejectedExecutionException ignored) {}
    }

    /** Kết nối TCP tới người gửi để tải nội dung file thật và lưu vào downloads/<username>. */
    private void taiTep(InetAddress address, int port, String token, String sender, String name, long size) {
        Path saved = null;
        Socket socket = new Socket();
        sockets.add(socket);
        try (socket) {
            if (closed) return;
            socket.connect(new InetSocketAddress(address, port), 5000);
            socket.setSoTimeout(15_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeUTF(token); out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            if (in.readLong() != size) throw new IOException("Kích thước tệp không khớp");
            Path directory = Path.of("downloads", username);
            Files.createDirectories(directory);
            saved = Files.createTempFile(directory, "group_", "_" + name);
            try (OutputStream target = Files.newOutputStream(saved)) {
                byte[] buffer = new byte[16 * 1024];
                long remaining = size;
                while (remaining > 0) {
                    int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new EOFException("Tệp chưa tải đủ");
                    target.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            if (closed) Files.deleteIfExists(saved);
            else onMessage.accept(new ChatMessage(ChatMessage.GROUP, sender,
                    name + "\nĐã lưu: " + saved.toAbsolutePath(), Instant.now(), false, true));
        } catch (IOException e) {
            if (saved != null) try { Files.deleteIfExists(saved); } catch (IOException ignored) {}
            if (!closed) onError.accept("Không tải được tệp " + name + ": " + e.getMessage());
        } finally { sockets.remove(socket); }
    }

    /** Đóng socket tạm, hủy các kết nối tải file và xóa danh sách token đang chia sẻ. */
    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) {}
        sockets.forEach(socket -> { try { socket.close(); } catch (IOException ignored) {} });
        shared.clear();
        workers.shutdownNow();
    }
}
