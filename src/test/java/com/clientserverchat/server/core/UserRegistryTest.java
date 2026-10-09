/*
 * File: UserRegistryTest.java
 * Vai trò: Test kho tài khoản của server.
 * Mục đích: Chứng minh đăng ký, đăng nhập, lưu mật khẩu rõ, IP, giờ vào/ra hoạt động đúng.
 */
package com.clientserverchat.server.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UserRegistryTest {
    @TempDir Path directory;

    /** Kiểm tra tài khoản lưu mật khẩu rõ, không còn salt/hash và đọc lại sau restart được. */
    @Test void credentialsAreStoredPlainTextAndSurviveRestart() throws Exception {
        Path file = directory.resolve("accounts.txt");
        UserRegistry registry = new UserRegistry(file);
        registry.dangKi("Alice", "Secret123!");
        registry.dangKi("Bob", "Secret123!");
        Path aliceFile = directory.resolve("Alice").resolve("accounts.txt");
        Path bobFile = directory.resolve("Bob").resolve("accounts.txt");
        String saved = Files.readString(aliceFile) + Files.readString(bobFile);
        assertFalse(saved.contains(".hash"));
        assertFalse(saved.contains(".salt"));
        Properties alice = new Properties(), bob = new Properties();
        try (var input = Files.newInputStream(aliceFile)) { alice.load(input); }
        try (var input = Files.newInputStream(bobFile)) { bob.load(input); }
        assertEquals("Alice", alice.getProperty("username"));
        assertEquals("Secret123!", alice.getProperty("password"));
        assertEquals("Bob", bob.getProperty("username"));
        assertEquals("Secret123!", bob.getProperty("password"));
        UserRegistry restored = new UserRegistry(file);
        restored.xacThuc("Alice", "Secret123!", false);
        assertThrows(IOException.class, () -> restored.xacThuc("Alice", "Wrong123!", false));
        assertThrows(IOException.class, () -> restored.xacThuc("Nobody", "Secret123!", false));
        assertThrows(IOException.class, () -> restored.dangKi("Alice", "Secret123!"));
    }

    /** Kiểm tra ghi IP, giờ vào/ra và không cho session cũ ghi logout đè session mới. */
    @Test void tracksIpEntryExitAndIgnoresOldSessionDisconnects() throws Exception {
        Path file = directory.resolve("accounts.txt");
        UserRegistry registry = new UserRegistry(file);
        registry.dangKi("Alice", "Secret123!");
        registry.ghiDangNhap("Alice", "session-one", "192.168.1.2");
        UserInfo online = registry.danhSachNguoiDung().getFirst();
        assertTrue(online.online());
        assertEquals("192.168.1.2", online.ip());
        assertNotNull(online.loginTime());
        assertNull(online.logoutTime());
        assertEquals("Secret123!", online.passwordDisplay());
        registry.ghiDangXuat("Alice", "session-one");
        UserInfo offline = registry.danhSachNguoiDung().getFirst();
        assertFalse(offline.online());
        assertFalse(offline.logoutTime().isBefore(offline.loginTime()));
        UserRegistry restored = new UserRegistry(file);
        assertEquals("Secret123!", restored.danhSachNguoiDung().getFirst().password());
        restored.xacThuc("Alice", "Secret123!", false);
        assertEquals(offline, restored.danhSachNguoiDung().getFirst());
        registry.ghiDangNhap("Alice", "session-two", "192.168.1.3");
        registry.ghiDangXuat("Alice", "session-one");
        assertTrue(registry.danhSachNguoiDung().getFirst().online());
        assertNull(registry.danhSachNguoiDung().getFirst().logoutTime());
        assertEquals("192.168.1.3", registry.danhSachNguoiDung().getFirst().ip());
    }

    /** Kiểm tra mật khẩu yếu hoặc tên tài khoản nguy hiểm không tạo tài khoản mới. */
    @Test void rejectsWeakPasswordsAndUnsafeNamesWithoutCreatingAccount() throws Exception {
        UserRegistry registry = new UserRegistry(directory.resolve("accounts.txt"));
        assertThrows(IOException.class, () -> registry.dangKi("Alice", "123"));
        assertThrows(IOException.class, () -> registry.dangKi("../Alice", "Secret123!"));
        assertTrue(registry.danhSachNguoiDung().isEmpty());
    }

    /** Kiểm tra mật khẩu luôn có sẵn để hiển thị trên server và vẫn không lộ qua toString debug. */
    @Test void passwordDisplayIsAlwaysAvailableAndPersistedAsPlainText() throws Exception {
        Path file = directory.resolve("accounts.txt");
        UserRegistry registry = new UserRegistry(file);
        registry.dangKi("Alice", "MậtKhẩu123!");
        assertEquals("MậtKhẩu123!", registry.danhSachNguoiDung().getFirst().passwordDisplay());
        assertFalse(registry.danhSachNguoiDung().getFirst().toString().contains("MậtKhẩu123!"));
        UserRegistry restored = new UserRegistry(file);
        List<List<UserInfo>> changes = new ArrayList<>();
        restored.khiDuLieuThayDoi(changes::add);
        assertEquals("MậtKhẩu123!", restored.danhSachNguoiDung().getFirst().passwordDisplay());
        assertThrows(IOException.class, () -> restored.xacThuc("Alice", "Wrong123!", false));
        assertEquals("MậtKhẩu123!", restored.danhSachNguoiDung().getFirst().password());
        restored.xacThuc("Alice", "MậtKhẩu123!", false);
        assertEquals("MậtKhẩu123!", changes.getLast().getFirst().passwordDisplay());
        Properties saved = new Properties();
        try (var input = Files.newInputStream(directory.resolve("Alice").resolve("accounts.txt"))) {
            saved.load(input);
        }
        assertEquals("Alice", saved.getProperty("username"));
        assertEquals("MậtKhẩu123!", saved.getProperty("password"));
    }
}
