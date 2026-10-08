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
    private final Map<String, Account> accounts = new TreeMap<>();
    private final Map<String, String> activeSessions = new HashMap<>();
    private Consumer<List<UserInfo>> onChange = ignored -> {};

    /** Dữ liệu tài khoản lưu nội bộ: mật khẩu rõ, IP, giờ vào, giờ ra. */
    private record Account(String password, String ip, Instant login, Instant logout) {}

    /** Mở file tài khoản, đọc dữ liệu format=2; file format cũ sẽ được bỏ qua. */
    public UserRegistry(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        if (!Files.exists(this.file)) return;
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(this.file)) { saved.load(input); }
        try {
            if (!"2".equals(saved.getProperty("format"))) return;
            for (String key : saved.stringPropertyNames()) {
                if (!key.endsWith(".password")) continue;
                String name = Protocol.validName(key.substring(0, key.length() - 9));
                String password = saved.getProperty(key, "");
                kiemTraMatKhau(password);
                accounts.put(name, new Account(password, saved.getProperty(name + ".ip", ""),
                        parseTime(saved.getProperty(name + ".login", "")), parseTime(saved.getProperty(name + ".logout", ""))));
            }
        } catch (RuntimeException e) { throw new IOException("Không đọc được dữ liệu tài khoản", e); }
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
        Files.createDirectories(file.getParent());
        Properties saved = new Properties();
        saved.setProperty("format", "2");
        for (var entry : accounts.entrySet()) {
            String name = entry.getKey();
            Account account = entry.getValue();
            saved.setProperty(name + ".password", account.password);
            saved.setProperty(name + ".ip", account.ip);
            saved.setProperty(name + ".login", account.login == null ? "" : account.login.toString());
            saved.setProperty(name + ".logout", account.logout == null ? "" : account.logout.toString());
        }
        Path temporary = Files.createTempFile(file.getParent(), "accounts-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { saved.store(output, "Server-Client Chat accounts - plain text passwords for networking lab"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Chuyển chuỗi thời gian ISO trong file dữ liệu thành Instant; chuỗi rỗng là null. */
    private static Instant parseTime(String value) { return value.isBlank() ? null : Instant.parse(value); }
}
