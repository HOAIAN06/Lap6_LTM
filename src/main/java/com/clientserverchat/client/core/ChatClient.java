/*
 * File: ChatClient.java
 * Vai trò: Tầng mạng TCP của client.
 * Mục đích: Đăng nhập/đăng ký, lấy danh sách online, gửi tin nhắn riêng và gửi file riêng qua server.
 * Phương thức chính:
 * - dangNhap()/dangKi(): xác thực tài khoản với server.
 * - lamMoiDanhSachNguoiDung(): yêu cầu server gửi danh sách online.
 * - guiTinNhan()/guiTep(): gửi dữ liệu riêng qua TCP.
 * - dangXuat()/close(): đóng kết nối và dọn tài nguyên.
 */
package com.clientserverchat.client.core;

import com.clientserverchat.common.Protocol;
import java.time.Instant;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** TCP client for authentication, online users, private messages and files. */
public final class ChatClient implements AutoCloseable {
    private final ExecutorService actions = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("client-actions").factory());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("client-deadlines").factory());
    private final Path downloadDirectory;
    private volatile Connection connection;
    private Consumer<List<String>> onUsers = ignored -> {};
    private Consumer<Boolean> onConnected = ignored -> {};
    private Consumer<ChatMessage> onChat = ignored -> {};
    private Consumer<String> onNotice = ignored -> {};

    /** Tạo client, file nhận được sẽ lưu trong thư mục downloads mặc định. */
    public ChatClient() { this(Path.of("downloads")); }

    /** Tạo client với thư mục lưu file nhận được do caller truyền vào. */
    public ChatClient(Path downloadDirectory) { this.downloadDirectory = downloadDirectory; }

    /** Đăng ký callback nhận danh sách người dùng online từ server. */
    public void setOnUsers(Consumer<List<String>> callback) { onUsers = Objects.requireNonNull(callback); }

    /** Đăng ký callback khi trạng thái kết nối TCP thay đổi. */
    public void setOnConnected(Consumer<Boolean> callback) { onConnected = Objects.requireNonNull(callback); }

    /** Đăng ký callback khi nhận tin nhắn hoặc file. */
    public void setOnChat(Consumer<ChatMessage> callback) { onChat = Objects.requireNonNull(callback); }

    /** Đăng ký callback khi cần báo lỗi/thông báo cho giao diện. */
    public void setOnNotice(Consumer<String> callback) { onNotice = Objects.requireNonNull(callback); }

    /** Lấy địa chỉ IP local của socket TCP để multicast chọn đúng card mạng. */
    public InetAddress layDiaChiMayClient() {
        Connection current = connection;
        return (current != null && current.socket.isConnected()) ? current.socket.getLocalAddress() : null;
    }

    /** Đăng nhập tài khoản đã có bằng lệnh LOGIN qua TCP. */
    public CompletableFuture<Void> dangNhap(String host, int port, String username, String password) {
        return ketNoiTaiKhoan(host, port, username, password, false);
    }

    /** Đăng ký tài khoản mới bằng lệnh SIGNUP qua TCP. */
    public CompletableFuture<Void> dangKi(String host, int port, String username, String password) {
        return ketNoiTaiKhoan(host, port, username, password, true);
    }

    /** Dùng chung cho đăng nhập và đăng ký: mở socket, gửi LOGIN/SIGNUP, bật luồng đọc phản hồi. */
    private CompletableFuture<Void> ketNoiTaiKhoan(String host, int port, String username, String password, boolean dangKi) {
        return chayNen(() -> {
            Authentication.kiemTraDangNhap(host, port, username, password);
            if (connection != null) throw new IOException("Da ket noi hoac dang ket noi");
            Connection next = new Connection(username);
            connection = next;
            try {
                next.socket.connect(new InetSocketAddress(host, port), 5000);
                next.socket.setTcpNoDelay(true);
                next.in = new DataInputStream(new BufferedInputStream(next.socket.getInputStream()));
                next.out = new DataOutputStream(new BufferedOutputStream(next.socket.getOutputStream()));
                Thread.ofPlatform().daemon().name("tcp-reader-" + username).start(next::readLoop);
                Protocol.Packet response = next.guiYeuCau(dangKi ? "SIGNUP" : "LOGIN", new byte[0], username, password);
                if (!response.type().equals("AUTHENTICATED")) throw new IOException("Server trả lời đăng nhập sai");
                synchronized (next) {
                    if (next.socket.isClosed()) throw new IOException("Ket noi da dong");
                    next.registered = true;
                    onConnected.accept(true);
                }
            } catch (Exception e) {
                next.close();
                throw e;
            }
        });
    }

    /** Gửi lệnh LIST để server trả lại danh sách người dùng đang online. */
    public CompletableFuture<Void> lamMoiDanhSachNguoiDung() {
        return chayNen(() -> {
            Connection current = layKetNoiDaDangNhap();
            current.guiYeuCau("LIST", new byte[0]);
        });
    }

    /** Gửi tin nhắn riêng tới một người dùng online thông qua server TCP. */
    public CompletableFuture<Void> guiTinNhan(String target, String text) {
        return chayNen(() -> {
            if (text.isBlank() || text.length() > 8000) throw new IOException("Tin nhan can 1-8000 ky tu");
            Connection current = layKetNoiDaDangNhap();
            current.guiYeuCau("MESSAGE", new byte[0], target, text);
            onChat.accept(new ChatMessage(target, current.username, text, Instant.now(), true, false));
        });
    }

    /** Đọc file thành byte và gửi cho người nhận qua server TCP. */
    public CompletableFuture<Void> guiTep(String target, File file) {
        return chayNen(() -> {
            Protocol.validFilename(file.getName());
            if (!file.isFile() || file.length() > Protocol.MAX_FILE_BYTES) {
                throw new IOException("Can chon file thuong, toi da 20 MiB");
            }
            byte[] data;
            try (InputStream input = Files.newInputStream(file.toPath())) {
                data = input.readNBytes(Protocol.MAX_FILE_BYTES + 1);
            }
            if (data.length > Protocol.MAX_FILE_BYTES) throw new IOException("File vuot 20 MiB");
            Connection current = layKetNoiDaDangNhap();
            current.guiYeuCau("FILE", data, target, file.getName());
            onChat.accept(new ChatMessage(target, current.username, file.getName(), Instant.now(), true, true));
        });
    }

    /** Đóng socket TCP hiện tại để đăng xuất hoặc hủy kết nối. */
    public void dangXuat() {
        Connection current = connection;
        if (current != null) current.close(); // Closing the socket immediately unblocks reads/writes.
    }

    /** Lấy connection hiện tại và báo lỗi nếu client chưa đăng nhập thành công. */
    private Connection layKetNoiDaDangNhap() throws IOException {
        Connection current = connection;
        if (current == null || !current.registered) throw new IOException("Chua ket noi server");
        return current;
    }

    /** Kiểu hàm cho tác vụ nền có thể ném exception. */
    @FunctionalInterface private interface Action { void run() throws Exception; }

    /** Chạy tác vụ mạng trên executor riêng để không khóa JavaFX UI thread. */
    private CompletableFuture<Void> chayNen(Action action) {
        return CompletableFuture.runAsync(() -> {
            try { action.run(); }
            catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, actions);
    }

    /** Đại diện cho một phiên TCP đã mở tới server. */
    private final class Connection implements AutoCloseable {
        final String username;
        final Socket socket = new Socket();
        final AtomicLong sequence = new AtomicLong();
        final Map<Long, CompletableFuture<Protocol.Packet>> pending = new ConcurrentHashMap<>();
        DataInputStream in;
        DataOutputStream out;
        volatile boolean registered;

        /** Lưu tên người dùng gắn với phiên TCP này. */
        Connection(String username) { this.username = username; }

        /** Gửi một request TCP có id, chờ phản hồi cùng id và xử lý timeout/lỗi server. */
        Protocol.Packet guiYeuCau(String type, byte[] data, String... fields) throws Exception {
            long id = sequence.incrementAndGet();
            CompletableFuture<Protocol.Packet> response = new CompletableFuture<>();
            pending.put(id, response);
            ScheduledFuture<?> timeout = deadlines.schedule(this::close, 30, TimeUnit.SECONDS);
            try {
                try {
                    synchronized (out) { Protocol.write(out, new Protocol.Packet(type, id, List.of(fields), data)); }
                } catch (IOException e) {
                    close(); // A partial write must never be followed by another frame.
                    throw e;
                }
                Protocol.Packet result = response.get(35, TimeUnit.SECONDS);
                if (result.type().equals("ERROR")) throw new IOException(result.field(0));
                return result;
            } catch (ExecutionException e) {
                throw new IOException(e.getCause().getMessage(), e.getCause());
            } finally {
                timeout.cancel(false);
                pending.remove(id);
            }
        }

        /** Chuyển danh sách USERS từ server sang callback, bỏ chính tài khoản hiện tại. */
        synchronized void deliverUsers(List<String> names) {
            if (connection == this) onUsers.accept(names.stream().filter(name -> !name.equals(username)).toList());
        }

        /** Luồng nền đọc liên tục packet từ server: phản hồi request, USERS, MESSAGE hoặc FILE. */
        void readLoop() {
            try {
                while (!socket.isClosed()) {
                    Protocol.Packet packet = Protocol.read(in);
                    if (packet.id() > 0) {
                        if (packet.type().equals("USERS")) deliverUsers(packet.fields());
                        CompletableFuture<Protocol.Packet> response = pending.get(packet.id());
                        if (response != null) response.complete(packet);
                        continue;
                    }
                    if (connection != this) continue;
                    switch (packet.type()) {
                        case "USERS" -> deliverUsers(packet.fields());
                        case "MESSAGE" -> {
                            onChat.accept(new ChatMessage(packet.field(0), packet.field(0), packet.field(1), Instant.now(), false, false));
                        }
                        case "FILE" -> nhanTep(packet);
                        default -> throw new IOException("Phan hoi khong hop le: " + packet.type());
                    }
                }
            } catch (IOException e) {
                if (!socket.isClosed() && registered) onNotice.accept("Mất kết nối server: " + e.getMessage());
            } finally { close(); }
        }

        /** Lưu file nhận được vào thư mục downloads/<username> và phát sự kiện chat file. */
        void nhanTep(Protocol.Packet packet) throws IOException {
            String name = Protocol.validFilename(packet.field(1));
            Path saved = null;
            try {
                Path directory = downloadDirectory.resolve(username);
                Files.createDirectories(directory);
                saved = Files.createTempFile(directory, "received_", "_" + name);
                Files.write(saved, packet.data());
                onChat.accept(new ChatMessage(packet.field(0), packet.field(0), name + "\nĐã lưu: " + saved.toAbsolutePath(),
                        Instant.now(), false, true));
            } catch (IOException e) {
                if (saved != null) {
                    try { Files.deleteIfExists(saved); } catch (IOException ignored) {}
                }
                onNotice.accept("Không lưu được file " + name + ": " + e.getMessage());
            }
        }

        /** Đóng socket, hủy các request đang chờ và báo UI rằng client đã mất kết nối. */
        @Override public synchronized void close() {
            boolean wasRegistered = registered;
            try { socket.close(); } catch (IOException ignored) {}
            registered = false;
            pending.values().forEach(future -> future.completeExceptionally(new IOException("Kết nối đã đóng hoặc hết thời gian chờ. Hãy kiểm tra server và thử lại.")));
            if (connection == this) {
                connection = null;
                onUsers.accept(List.of());
                if (wasRegistered) onConnected.accept(false);
            }
        }
    }

    /** Đóng toàn bộ client: đăng xuất và dừng executor nền. */
    @Override public void close() {
        dangXuat();
        actions.shutdownNow();
        deadlines.shutdownNow();
    }
}
