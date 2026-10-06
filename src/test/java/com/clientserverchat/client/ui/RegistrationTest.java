package com.clientserverchat.client.ui;

import com.clientserverchat.client.core.ChatClient;
import com.clientserverchat.server.core.ChatServer;
import com.clientserverchat.server.core.UserRegistry;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    @BeforeAll static void startJavaFx() throws Exception {
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.complete(null);
        });
        started.get(10, TimeUnit.SECONDS);
    }

    @BeforeEach void setUp() throws Exception {
        registry = new UserRegistry(directory.resolve("accounts.properties"));
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

    @AfterEach void tearDown() throws Exception {
        if (controller != null) fx(controller::close);
        if (server != null) server.close();
    }

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
        assertTrue(registry.snapshot().isEmpty());
    }

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
        assertTrue(registry.snapshot().isEmpty());
        server.close();
        fx(() -> view.connectServer.fire());
        awaitUi(() -> !view.connectServer.isDisabled());
        fx(() -> assertTrue(view.serverStatus.getText().contains("Không thể kết nối")));
    }

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
            view.groupRoom.fire();
            assertEquals("○ Chưa tham gia", view.roomStatus.getText());
            assertEquals("Tham gia phòng", view.groupAction.getText());
            assertTrue(view.send.isDisabled());
            assertTrue(view.attach.isDisabled());
        });
        assertEquals(1, registry.snapshot().size());
        assertTrue(registry.snapshot().getFirst().online());
    }

    @Test void duplicateAccountShowsServerErrorAndCanRetry() throws Exception {
        registry.register("Minh_Đức", "Original123!");
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
        assertEquals(2, registry.snapshot().size());
    }

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

    private static void fx(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

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
