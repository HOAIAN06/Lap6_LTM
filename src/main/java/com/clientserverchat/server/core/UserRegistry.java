package com.clientserverchat.server.core;

import com.clientserverchat.common.Protocol;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Account persistence, password verification and latest connection metadata; no JavaFX dependency. */
public final class UserRegistry {
    private static final int ITERATIONS = 210_000;
    private final Path file;
    private final Map<String, Account> accounts = new TreeMap<>();
    private final Map<String, String> activeSessions = new HashMap<>();
    private final Map<String, String> sessionPasswords = new HashMap<>();
    private Consumer<List<UserInfo>> onChange = ignored -> {};

    private record Account(String salt, String hash, String ip, Instant login, Instant logout) {}

    public UserRegistry(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        if (!Files.exists(this.file)) return;
        Properties saved = new Properties();
        try (InputStream input = Files.newInputStream(this.file)) { saved.load(input); }
        try {
            if (!"1".equals(saved.getProperty("format"))) throw new IOException("Định dạng dữ liệu tài khoản không hỗ trợ");
            for (String key : saved.stringPropertyNames()) {
                if (!key.endsWith(".hash")) continue;
                String name = Protocol.validName(key.substring(0, key.length() - 5));
                String salt = saved.getProperty(name + ".salt");
                String hash = saved.getProperty(key);
                if (Base64.getDecoder().decode(salt).length != 16 || Base64.getDecoder().decode(hash).length != 32) {
                    throw new IOException("Dữ liệu tài khoản bị hỏng");
                }
                accounts.put(name, new Account(salt, hash, saved.getProperty(name + ".ip", ""),
                        parseTime(saved.getProperty(name + ".login", "")), parseTime(saved.getProperty(name + ".logout", ""))));
            }
        } catch (RuntimeException e) { throw new IOException("Không đọc được dữ liệu tài khoản", e); }
    }

    public synchronized void setOnChange(Consumer<List<UserInfo>> callback) {
        onChange = Objects.requireNonNull(callback);
        publish();
    }

    public synchronized List<UserInfo> snapshot() {
        return accounts.entrySet().stream().map(entry -> {
            Account account = entry.getValue();
            return new UserInfo(entry.getKey(), account.ip, account.login, account.logout,
                    activeSessions.containsKey(entry.getKey()), sessionPasswords.get(entry.getKey()));
        }).toList();
    }

    public synchronized void register(String name, String password) throws IOException {
        Protocol.validName(name);
        validatePassword(password);
        if (accounts.containsKey(name)) throw new IOException("Tài khoản đã tồn tại. Hãy chọn Đăng nhập.");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        Account account = new Account(Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(derive(password, salt)), "", null, null);
        accounts.put(name, account);
        try { save(); } catch (IOException e) { accounts.remove(name); throw e; }
        sessionPasswords.put(name, password);
        publish();
    }

    public synchronized void authenticate(String name, String password, boolean signUp) throws IOException {
        Protocol.validName(name);
        validatePassword(password);
        if (signUp) { register(name, password); return; }
        Account account = accounts.get(name);
        if (account == null || !MessageDigest.isEqual(Base64.getDecoder().decode(account.hash),
                derive(password, Base64.getDecoder().decode(account.salt)))) {
            throw new IOException("Tên đăng nhập hoặc mật khẩu không đúng");
        }
        sessionPasswords.put(name, password);
        publish();
    }

    public synchronized void connected(String name, String sessionId, String ip) throws IOException {
        Account old = accounts.get(name);
        if (old == null) throw new IOException("Tài khoản không tồn tại");
        accounts.put(name, new Account(old.salt, old.hash, ip, Instant.now(), null));
        try { save(); } catch (IOException e) { accounts.put(name, old); throw e; }
        activeSessions.put(name, sessionId);
        publish();
    }

    public synchronized void disconnected(String name, String sessionId) throws IOException {
        if (!activeSessions.remove(name, sessionId)) return;
        Account old = accounts.get(name);
        accounts.put(name, new Account(old.salt, old.hash, old.ip, old.login, Instant.now()));
        try { save(); } finally { publish(); }
    }

    private void publish() { onChange.accept(snapshot()); }

    private static void validatePassword(String password) throws IOException {
        if (password == null || password.isBlank() || password.length() < 6 || password.length() > 128) {
            throw new IOException("Mật khẩu cần từ 6 đến 128 ký tự");
        }
    }

    private static byte[] derive(String password, byte[] salt) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IOException("Không thể xử lý mật khẩu", e); }
        finally { spec.clearPassword(); }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Properties saved = new Properties();
        saved.setProperty("format", "1");
        for (var entry : accounts.entrySet()) {
            String name = entry.getKey();
            Account account = entry.getValue();
            saved.setProperty(name + ".salt", account.salt);
            saved.setProperty(name + ".hash", account.hash);
            saved.setProperty(name + ".ip", account.ip);
            saved.setProperty(name + ".login", account.login == null ? "" : account.login.toString());
            saved.setProperty(name + ".logout", account.logout == null ? "" : account.logout.toString());
        }
        Path temporary = Files.createTempFile(file.getParent(), "accounts-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { saved.store(output, "Server-Client Chat accounts - PBKDF2WithHmacSHA256"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Instant parseTime(String value) { return value.isBlank() ? null : Instant.parse(value); }
}
