/*
 * File: UserRegistry.java
 * Vai trò: Kho dữ liệu tài khoản phía server.
 * Mục đích: Lưu/đọc tài khoản, kiểm tra mật khẩu rõ, ghi IP và giờ đăng nhập/đăng xuất.
 * Phương thức chính:
 * - dangKi()/xacThuc(): tạo tài khoản và kiểm tra đăng nhập.
 * - ghiDangNhap()/ghiDangXuat(): cập nhật trạng thái phiên.
 * - danhSachNguoiDung(): tạo dữ liệu cho bảng server.
 * - luu(): ghi dữ liệu xuống file accounts.txt trong thư mục data/server.
 */
package com.clientserverchat.server.core;

import com.clientserverchat.common.Protocol;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Account persistence, password verification and latest connection metadata; no JavaFX dependency. */
public final class UserRegistry {
    private final Path file;
    private final Path usersDir;
    private final Map<String, Account> accounts = new TreeMap<>();
    private final Map<String, String> activeSessions = new HashMap<>();
    private Consumer<List<UserInfo>> onChange = ignored -> {};

    /** Dữ liệu tài khoản lưu nội bộ: mật khẩu rõ, IP, giờ vào, giờ ra. */
    private record Account(String password, String ip, Instant login, Instant logout) {}

    /** Mở file tài khoản, đọc dữ liệu format=2; file format cũ sẽ được bỏ qua. */
    public UserRegistry(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        this.usersDir = this.file.getParent();
        if (this.usersDir == null || !Files.exists(this.usersDir)) return;

        // Read per-user directories: data/server/<username>/accounts.txt
        try (var stream = Files.list(this.usersDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Path accFile = dir.resolve("accounts.txt");
                if (!Files.exists(accFile)) return;
                Properties saved = new Properties();
                try (InputStream input = Files.newInputStream(accFile)) {
                    saved.load(input);
                    String name = saved.getProperty("username", dir.getFileName().toString());
                    name = Protocol.validName(name);
                    String password = saved.getProperty("password", "");
                    kiemTraMatKhau(password);
                    String ip = saved.getProperty("ip", "");
                    Instant login = parseTime(saved.getProperty("login", ""));
                    Instant logout = parseTime(saved.getProperty("logout", ""));
                    accounts.put(name, new Account(password, ip, login, logout));
                } catch (IOException | RuntimeException e) {
                    // skip malformed account file
                }
            });
        }
    }

    /** Đăng ký callback để giao diện server tự cập nhật khi dữ liệu tài khoản thay đổi. */
    public synchronized void khiDuLieuThayDoi(Consumer<List<UserInfo>> callback) {
        onChange = Objects.requireNonNull(callback);
        phatDuLieuMoi();
    }

    /** Trả snapshot tất cả tài khoản để bảng server hiển thị. */
    public synchronized List<UserInfo> danhSachNguoiDung() {
        return accounts.entrySet().stream().map(entry -> {
            Account account = entry.getValue();
            return new UserInfo(entry.getKey(), account.ip, account.login, account.logout,
                    activeSessions.containsKey(entry.getKey()), account.password);
        }).toList();
    }

    /** Tạo tài khoản mới, kiểm tra tên/mật khẩu và lưu mật khẩu rõ xuống file. */
    public synchronized void dangKi(String name, String password) throws IOException {
        Protocol.validName(name);
        kiemTraMatKhau(password);
        if (accounts.containsKey(name)) throw new IOException("Tài khoản đã tồn tại. Hãy chọn Đăng nhập.");
        Account account = new Account(password, "", null, null);
        accounts.put(name, account);
        try { luu(); } catch (IOException e) { accounts.remove(name); throw e; }
        phatDuLieuMoi();
    }

    /** Xác thực đăng nhập hoặc chuyển sang đăng ký nếu request là SIGNUP. */
    public synchronized void xacThuc(String name, String password, boolean dangKiMoi) throws IOException {
        Protocol.validName(name);
        kiemTraMatKhau(password);
        if (dangKiMoi) { dangKi(name, password); return; }
        Account account = accounts.get(name);
        if (account == null || !account.password.equals(password)) {
            throw new IOException("Tên đăng nhập hoặc mật khẩu không đúng");
        }
        phatDuLieuMoi();
    }

    /** Ghi IP, giờ đăng nhập và session id khi client đăng nhập thành công. */
    public synchronized void ghiDangNhap(String name, String sessionId, String ip) throws IOException {
        Account old = accounts.get(name);
        if (old == null) throw new IOException("Tài khoản không tồn tại");
        accounts.put(name, new Account(old.password, ip, Instant.now(), null));
        try { luu(); } catch (IOException e) { accounts.put(name, old); throw e; }
        activeSessions.put(name, sessionId);
        phatDuLieuMoi();
    }

    /** Ghi giờ đăng xuất khi đúng session id đang online bị đóng. */
    public synchronized void ghiDangXuat(String name, String sessionId) throws IOException {
        if (!activeSessions.remove(name, sessionId)) return;
        Account old = accounts.get(name);
        accounts.put(name, new Account(old.password, old.ip, old.login, Instant.now()));
        try { luu(); } finally { phatDuLieuMoi(); }
    }

    /** Phát snapshot mới cho UI server sau mỗi thay đổi. */
    private void phatDuLieuMoi() { onChange.accept(danhSachNguoiDung()); }

    /** Kiểm tra mật khẩu rõ có độ dài hợp lệ và không toàn khoảng trắng. */
    private static void kiemTraMatKhau(String password) throws IOException {
        if (password == null || password.isBlank() || password.length() < 6 || password.length() > 128) {
            throw new IOException("Mật khẩu cần từ 6 đến 128 ký tự");
        }
    }

    /** Ghi toàn bộ tài khoản xuống file properties bằng file tạm rồi replace an toàn. */
    private void luu() throws IOException {
        // Persist each account into its own directory: data/server/<username>/accounts.txt
        if (usersDir == null) throw new IOException("Users directory is undefined");
        Files.createDirectories(usersDir);
        for (var entry : accounts.entrySet()) {
            String name = entry.getKey();
            Account account = entry.getValue();
            Path dir = usersDir.resolve(name);
            Files.createDirectories(dir);
            Properties saved = new Properties();
            saved.setProperty("username", name);
            saved.setProperty("password", account.password);
            saved.setProperty("ip", account.ip == null ? "" : account.ip);
            saved.setProperty("login", account.login == null ? "" : account.login.toString());
            saved.setProperty("logout", account.logout == null ? "" : account.logout.toString());
            Path temporary = Files.createTempFile(dir, "accounts-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) { saved.store(output, "Server-Client Chat account - plain text password"); }
                try { Files.move(temporary, dir.resolve("accounts.txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, dir.resolve("accounts.txt"), StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    /** Chuyển chuỗi thời gian ISO trong file dữ liệu thành Instant; chuỗi rỗng là null. */
    private static Instant parseTime(String value) { return value.isBlank() ? null : Instant.parse(value); }
}
