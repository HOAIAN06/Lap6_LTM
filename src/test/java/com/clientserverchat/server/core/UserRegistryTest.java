package com.clientserverchat.server.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UserRegistryTest {
    @TempDir Path directory;

    @Test void credentialsAreHashedAndSurviveRestart() throws Exception {
        Path file = directory.resolve("accounts.properties");
        UserRegistry registry = new UserRegistry(file);
        registry.register("Alice", "Secret123!");
        registry.register("Bob", "Secret123!");
        String saved = Files.readString(file);
        assertFalse(saved.contains("Secret123!"));
        Properties values = new Properties();
        try (var input = Files.newInputStream(file)) { values.load(input); }
        assertNotEquals(values.getProperty("Alice.hash"), values.getProperty("Bob.hash"));
        UserRegistry restored = new UserRegistry(file);
        restored.authenticate("Alice", "Secret123!", false);
        assertThrows(IOException.class, () -> restored.authenticate("Alice", "Wrong123!", false));
        assertThrows(IOException.class, () -> restored.authenticate("Nobody", "Secret123!", false));
        assertThrows(IOException.class, () -> restored.register("Alice", "Secret123!"));
    }

    @Test void tracksIpEntryExitAndIgnoresOldSessionDisconnects() throws Exception {
        Path file = directory.resolve("accounts.properties");
        UserRegistry registry = new UserRegistry(file);
        registry.register("Alice", "Secret123!");
        registry.connected("Alice", "session-one", "192.168.1.2");
        UserInfo online = registry.snapshot().getFirst();
        assertTrue(online.online());
        assertEquals("192.168.1.2", online.ip());
        assertNotNull(online.loginTime());
        assertNull(online.logoutTime());
        assertEquals("Secret123!", online.passwordDisplay());
        registry.disconnected("Alice", "session-one");
        UserInfo offline = registry.snapshot().getFirst();
        assertFalse(offline.online());
        assertFalse(offline.logoutTime().isBefore(offline.loginTime()));
        UserRegistry restored = new UserRegistry(file);
        assertNull(restored.snapshot().getFirst().password());
        restored.authenticate("Alice", "Secret123!", false);
        assertEquals(offline, restored.snapshot().getFirst());
        registry.connected("Alice", "session-two", "192.168.1.3");
        registry.disconnected("Alice", "session-one");
        assertTrue(registry.snapshot().getFirst().online());
        assertNull(registry.snapshot().getFirst().logoutTime());
        assertEquals("192.168.1.3", registry.snapshot().getFirst().ip());
    }

    @Test void rejectsWeakPasswordsAndUnsafeNamesWithoutCreatingAccount() throws Exception {
        UserRegistry registry = new UserRegistry(directory.resolve("accounts.properties"));
        assertThrows(IOException.class, () -> registry.register("Alice", "123"));
        assertThrows(IOException.class, () -> registry.register("../Alice", "Secret123!"));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test void passwordDisplayIsUpdatedOnlyBySuccessfulAuthenticationAndNeverPersisted() throws Exception {
        Path file = directory.resolve("accounts.properties");
        UserRegistry registry = new UserRegistry(file);
        registry.register("Alice", "MậtKhẩu123!");
        assertEquals("MậtKhẩu123!", registry.snapshot().getFirst().passwordDisplay());
        assertFalse(registry.snapshot().getFirst().toString().contains("MậtKhẩu123!"));
        UserRegistry restored = new UserRegistry(file);
        List<List<UserInfo>> changes = new ArrayList<>();
        restored.setOnChange(changes::add);
        assertEquals("Chưa có trong phiên", restored.snapshot().getFirst().passwordDisplay());
        assertThrows(IOException.class, () -> restored.authenticate("Alice", "Wrong123!", false));
        assertNull(restored.snapshot().getFirst().password());
        restored.authenticate("Alice", "MậtKhẩu123!", false);
        assertEquals("MậtKhẩu123!", changes.getLast().getFirst().passwordDisplay());
        assertFalse(Files.readString(file).contains(".password"));
    }
}
