/*
 * File: RegistrationTest.java
 * Vai trò: Test giao diện đăng nhập/đăng ký client.
 * Mục đích: Kiểm tra form JavaFX, nút Kết nối, đăng ký, lỗi nhập lại mật khẩu và lỗi server.
 */
package com.clientserverchat.client.ui;

import com.clientserverchat.client.core.ChatClient;
import com.clientserverchat.client.core.BroadcastFileService;
import com.clientserverchat.client.core.ChatMessage;
import com.clientserverchat.server.core.ChatServer;
import com.clientserverchat.server.core.UserRegistry;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class RegistrationTest {
    @TempDir Path directory;
    private ChatServer server;
    private UserRegistry registry;
    private ClientView view;
    private ClientController controller;
    private javafx.scene.Node loginPage;

    /** Khởi động JavaFX toolkit một lần để các test UI có thể tạo Scene/Control. */
    @BeforeAll static void startJavaFx() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.complete(null);
        });
        started.get(10, TimeUnit.SECONDS);
    }

    /** Tạo server test, client view/controller và điền sẵn form đăng ký trước mỗi test. */
    @BeforeEach void setUp() throws Exception {
        registry = new UserRegistry(directory.resolve("accounts.txt"));
        server = new ChatServer("127.0.0.1", 0, registry, ignored -> {});
        server.start();
        fx(() -> {
            view = new ClientView();
            controller = new ClientController(new ChatClient(directory), view);
            Scene scene = new Scene(view, 1140, 760);
            scene.getStylesheets().add(getClass().getResource("/styles/chat.css").toExternalForm());
            view.applyCss();
            view.layout();
            loginPage = view.getCenter();
            view.port.setText(Integer.toString(server.getPort()));
            view.signUp.fire();
            view.username.setText("Minh_Đức");
            view.password.setText(" Secret123! ");
            view.confirmPassword.setText(" Secret123! ");
        });
    }

    /** Dọn controller và server sau mỗi test để không giữ socket/luồng nền. */
    @AfterEach void tearDown() throws Exception {
        if (controller != null) fx(controller::close);
        if (server != null) server.close();
    }

    /** Kiểm tra nhập lại mật khẩu sai thì vẫn ở form login và highlight ô xác nhận. */
    @Test void invalidConfirmationStaysOnFormAndHighlightsField() throws Exception {
        fx(() -> {
            view.confirmPassword.setText("Secret123!");
            view.connect.fire();
            assertSame(loginPage, view.getCenter());
            assertTrue(view.loginError.getText().contains("không khớp"));
            assertTrue(view.confirmPassword.getPseudoClassStates().contains(PseudoClass.getPseudoClass("invalid")));
            assertFalse(view.connect.isDisabled());
            view.confirmPassword.setText(" Secret123! ");
            assertEquals("", view.loginError.getText());
            assertFalse(view.confirmPassword.getPseudoClassStates().contains(PseudoClass.getPseudoClass("invalid")));
        });
        assertTrue(registry.danhSachNguoiDung().isEmpty());
    }

    /** Kiểm tra nút Kết nối chỉ test TCP server, không đăng nhập hay tạo tài khoản. */
    @Test void serverCheckShowsFeedbackWithoutLoggingIn() throws Exception {
        fx(() -> {
            view.connectServer.fire();
            assertTrue(view.connectServer.isDisabled());
            assertTrue(view.serverStatus.isVisible());
            assertEquals("Đang kết nối…", view.connectServer.getText());
        });
        awaitUi(() -> !view.connectServer.isDisabled());
        fx(() -> {
            assertEquals("● Kết nối thành công", view.serverStatus.getText());
            assertSame(loginPage, view.getCenter());
        });
        assertTrue(registry.danhSachNguoiDung().isEmpty());
        server.close();
        fx(() -> view.connectServer.fire());
        awaitUi(() -> !view.connectServer.isDisabled());
        fx(() -> assertTrue(view.serverStatus.getText().contains("Không thể kết nối")));
    }

    /** Kiểm tra đăng ký khóa form, mở màn hình chat và xóa mật khẩu trên form. */
    @Test void signupLocksFormThenOpensChatAndClearsSecrets() throws Exception {
        fx(() -> {
            view.connect.fire();
            assertTrue(view.connect.isDisabled());
            assertTrue(view.username.isDisabled());
            assertTrue(view.signIn.isDisabled());
            assertEquals("Đang đăng ký…", view.connect.getText());
            view.connect.fire(); // A second click must not submit another SIGNUP.
        });
        awaitUi(() -> view.getCenter() != loginPage);
        fx(() -> {
            assertTrue(view.notice.getText().contains("Đăng ký thành công"));
            assertEquals("", view.password.getText());
            assertEquals("", view.confirmPassword.getText());
            assertFalse(view.connect.isDisabled());
            assertFalse(view.attach.isDisabled()); // Chưa chọn cuộc trò chuyện vẫn gửi tệp được.
            assertEquals("📎  Tệp cho tất cả", view.attach.getText());
            view.groupRoom.fire();
            assertEquals("○ Chưa tham gia", view.roomStatus.getText());
            assertEquals("Tham gia phòng", view.groupAction.getText());
            assertTrue(view.send.isDisabled());
            assertFalse(view.attach.isDisabled());
            assertEquals("📎  Tệp cho tất cả", view.attach.getText());
        });
        assertEquals(1, registry.danhSachNguoiDung().size());
        assertTrue(registry.danhSachNguoiDung().getFirst().online());
    }

    /** Kiểm tra tài khoản trùng báo lỗi server và người dùng có thể đổi tên để đăng ký lại. */
    @Test void fileSentFromPrivateChatAlsoReachesAnotherClient() throws Exception {
        BlockingQueue<ChatMessage> outside = new LinkedBlockingQueue<>();
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        try (ChatClient bob = new ChatClient(directory);
             BroadcastFileService charlie = new BroadcastFileService("OutsideCharlie", null, outside::add, errors::add)) {
            bob.dangKi("127.0.0.1", server.getPort(), "PrivateBob", "Secret123!").get(5, TimeUnit.SECONDS);
            fx(() -> view.connect.fire());
            awaitUi(() -> view.getCenter() != loginPage && view.users.getItems().contains("PrivateBob"));
            byte[] bytes = "Tệp dành cho tất cả client".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path source = Files.write(directory.resolve("broadcast-from-private.txt"), bytes);
            fx(() -> {
                view.users.getSelectionModel().select("PrivateBob");
                assertEquals("PrivateBob", view.roomTitle.getText());
                assertEquals("📎  Tệp cho tất cả", view.attach.getText());
                assertFalse(view.attach.isDisabled());
                try {
                    var sendFile = ClientController.class.getDeclaredMethod("guiTep", java.io.File.class);
                    sendFile.setAccessible(true);
                    sendFile.invoke(controller, source.toFile());
                } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            });
            ChatMessage received = outside.poll(5, TimeUnit.SECONDS);
            assertNotNull(received, "C phải nhận tệp dù A đang mở chat riêng với B");
            Path saved = Path.of(received.content().split("\nĐã lưu: ", 2)[1]);
            try { assertArrayEquals(bytes, Files.readAllBytes(saved)); }
            finally { Files.deleteIfExists(saved); }
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }

    /** Kiểm tra tài khoản trùng báo lỗi server và người dùng có thể đổi tên để đăng ký lại. */
    @Test void duplicateAccountShowsServerErrorAndCanRetry() throws Exception {
        registry.dangKi("Minh_Đức", "Original123!");
        fx(() -> view.connect.fire());
        awaitUi(() -> !view.connect.isDisabled() && !view.loginError.getText().isEmpty());
        fx(() -> {
            assertSame(loginPage, view.getCenter());
            assertTrue(view.loginError.getText().contains("đã tồn tại"));
            assertTrue(view.signUp.isSelected());
            view.username.setText("AnotherUser");
            view.connect.fire();
        });
        awaitUi(() -> view.getCenter() != loginPage);
        assertEquals(2, registry.danhSachNguoiDung().size());
    }

    /** Kiểm tra chuyển từ Đăng ký sang Đăng nhập sẽ ẩn ô xác nhận và xóa lỗi validate. */
    @Test void switchingToLoginHidesConfirmationAndClearsValidation() throws Exception {
        fx(() -> {
            view.confirmPassword.clear();
            view.connect.fire();
            assertFalse(view.loginError.getText().isEmpty());
            view.signIn.fire();
            assertFalse(view.confirmPassword.getParent().isVisible());
            assertFalse(view.confirmPassword.getParent().isManaged());
            assertEquals("", view.loginError.getText());
            view.signIn.fire();
            assertTrue(view.signIn.isSelected());
            view.signUp.fire();
            assertTrue(view.confirmPassword.getParent().isVisible());
            assertEquals("", view.confirmPassword.getText());
        });
    }

    /** Kiểm tra server tắt thì form mở khóa lại và báo lỗi dễ hiểu. */
    @Test void unavailableServerShowsActionableErrorAndUnlocksForm() throws Exception {
        server.close();
        fx(() -> view.connect.fire());
        awaitUi(() -> !view.connect.isDisabled() && !view.loginError.getText().isEmpty());
        fx(() -> {
            assertSame(loginPage, view.getCenter());
            assertTrue(view.loginError.getText().contains("khởi động server"));
            assertFalse(view.loginError.getText().contains("getsockopt"));
            assertFalse(view.host.isDisabled());
            assertEquals("Minh_Đức", view.username.getText());
        });
    }

    /** Chạy một đoạn code trên JavaFX Application Thread và chờ hoàn tất. */
    private static void fx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    /** Chờ đến khi điều kiện UI đúng, dùng cho các thao tác async. */
    private static void awaitUi(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            FutureTask<Boolean> task = new FutureTask<>(condition::getAsBoolean);
            Platform.runLater(task);
            if (task.get(5, TimeUnit.SECONDS)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for authentication UI");
    }
}
