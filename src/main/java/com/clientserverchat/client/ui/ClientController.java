package com.clientserverchat.client.ui;

import com.clientserverchat.client.core.ChatClient;
import com.clientserverchat.client.core.ChatMessage;
import com.clientserverchat.client.core.Authentication;
import com.clientserverchat.client.core.MulticastChatService;
import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Binds UI actions to the network service; all UI state is confined to the JavaFX thread. */
public final class ClientController implements AutoCloseable {
    private final ChatClient client;
    private final ClientView view;
    private final MulticastChatService multicastService = new MulticastChatService();
    private final Map<String, List<ChatMessage>> conversations = new HashMap<>();
    private final Map<String, String> drafts = new HashMap<>();
    private List<String> online = List.of();
    private String active;
    private String loginName;
    private String serverAddress;
    private boolean connected;
    private boolean joining;
    private boolean sending;
    private boolean refreshingList;

    public ClientController(ChatClient client, ClientView view) {
        this.client = client;
        this.view = view;

        // Lắng nghe các sự kiện từ TCP Client
        client.setOnNotice(text -> Platform.runLater(() -> { view.showNotice(text); view.loginError.setText(text); }));
        client.setOnUsers(names -> Platform.runLater(() -> { online = names; filterUsers(); update(); }));
        client.setOnConnected(value -> Platform.runLater(() -> {
            connected = value;
            loginBusy(false);
            if (value) {
                boolean registered = view.signUp.isSelected();
                view.password.clear();
                view.confirmPassword.clear();
                view.signIn.setSelected(true);
                view.showChat(loginName, serverAddress);
                if (registered) view.showNotice("Đăng ký thành công! Chào mừng " + loginName + ".");
            } else {
                multicastService.leaveGroup();
                joining = false;
                sending = false;
                view.showLogin();
                view.loginError.setText("Đã ngắt kết nối. Bạn có thể đăng nhập lại.");
            }
            update();
        }));
        client.setOnChat(item -> Platform.runLater(() -> {
            List<ChatMessage> history = conversations.computeIfAbsent(item.conversation(), ignored -> new ArrayList<>());
            history.add(item);
            if (history.size() > 500) history.removeFirst();
            if (Objects.equals(active, item.conversation())) render();
            else if (!item.outgoing()) view.showNotice("Tin mới từ " + item.sender());
        }));

        // Lắng nghe các sự kiện từ UDP Multicast Service (tách biệt hoàn toàn network khỏi UI)
        multicastService.setOnMessageReceived(item -> Platform.runLater(() -> {
            List<ChatMessage> history = conversations.computeIfAbsent(ChatMessage.GROUP, ignored -> new ArrayList<>());
            history.add(item);
            if (history.size() > 500) history.removeFirst();
            if (Objects.equals(active, ChatMessage.GROUP)) render();
            else if (!item.outgoing()) view.showNotice("Tin nhóm mới từ " + item.sender());
        }));
        multicastService.setOnSystemNotice(noticeText -> Platform.runLater(() -> {
            ChatMessage systemMsg = new ChatMessage(ChatMessage.GROUP, "Hệ thống", noticeText, Instant.now(), false, false);
            List<ChatMessage> history = conversations.computeIfAbsent(ChatMessage.GROUP, ignored -> new ArrayList<>());
            history.add(systemMsg);
            if (Objects.equals(active, ChatMessage.GROUP)) render();
            view.showNotice(noticeText);
        }));
        multicastService.setOnError(err -> Platform.runLater(() -> {
            view.showNotice(err);
        }));
        multicastService.setOnGroupStateChanged(isJoined -> Platform.runLater(() -> {
            joining = false;
            update();
        }));

        // Gắn sự kiện các nút giao diện
        view.connect.setOnAction(event -> connect());
        view.connectServer.setOnAction(event -> handleServerConnect());
        view.host.setOnAction(event -> view.port.requestFocus());
        view.port.setOnAction(event -> view.username.requestFocus());
        view.username.setOnAction(event -> view.password.requestFocus());
        view.password.setOnAction(event -> {
            if (view.signUp.isSelected()) view.confirmPassword.requestFocus();
            else connect();
        });
        view.confirmPassword.setOnAction(event -> connect());
        view.disconnect.setOnAction(event -> {
            multicastService.leaveGroup();
            client.disconnect();
        });
        view.signUp.selectedProperty().addListener((observable, before, after) -> {
            view.confirmPassword.clear();
            view.clearAuthenticationError();
        });
        view.refresh.setOnAction(event -> report(client.refreshUsers()));
        view.search.textProperty().addListener((observable, old, value) -> filterUsers());
        view.users.getSelectionModel().selectedItemProperty().addListener((observable, old, value) -> {
            if (!refreshingList && value != null) select(value);
        });

        // Opening the room does not subscribe to multicast.
        view.groupRoom.setOnAction(event -> select(ChatMessage.GROUP));
        view.groupAction.setOnAction(event -> toggleMulticastGroup());
        view.leaveGroupHeader.setOnAction(event -> toggleMulticastGroup());
        view.backButton.setOnAction(event -> {
            active = null;
            view.users.getSelectionModel().clearSelection();
            view.showConversation(List.of());
            update();
        });

        view.send.setOnAction(event -> send());
        view.message.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) { event.consume(); send(); }
        });
        view.attach.setOnAction(event -> sendFile(view.chooseFile()));
        view.messageScroll.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles() && !view.attach.isDisabled()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        view.messageScroll.setOnDragDropped(event -> {
            var db = event.getDragboard();
            if (db.hasFiles() && !view.attach.isDisabled()) {
                List<File> files = db.getFiles();
                if (!files.isEmpty()) {
                    sendFile(files.getFirst());
                    event.setDropCompleted(true);
                }
            }
            event.consume();
        });

        update();
    }

    private void toggleMulticastGroup() {
        if (!connected || joining) return;
        select(ChatMessage.GROUP);
        if (multicastService.isJoined()) {
            leaveMulticastGroup();
        } else {
            joinMulticastGroup();
        }
    }

    private void joinMulticastGroup() {
        if (!connected || loginName == null || joining) return;
        joining = true;
        update();
        CompletableFuture.runAsync(() -> {
            try {
                multicastService.joinGroup(loginName, client.getLocalAddress());
            } catch (Exception e) {
                Platform.runLater(() -> {
                    joining = false;
                    error(e);
                    update();
                });
            }
        });
    }

    private void leaveMulticastGroup() {
        joining = true;
        update();
        CompletableFuture.runAsync(() -> {
            multicastService.leaveGroup();
            Platform.runLater(() -> {
                joining = false;
                update();
            });
        });
    }

    private void sendFile(File file) {
        if (file == null || active == null || view.attach.isDisabled()) return;
        sending = true;
        String destination = active;
        update();
        CompletableFuture<Void> operation = ChatMessage.GROUP.equals(destination)
                ? CompletableFuture.runAsync(() -> {
                    try { multicastService.sendFile(file); }
                    catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
                }) : client.sendFile(destination, file);
        operation.whenComplete((unused, error) -> Platform.runLater(() -> {
            sending = false;
            if (error != null) error(error);
            update();
        }));
    }

    private void handleServerConnect() {
        view.clearAuthenticationError();
        if (view.connectServer.isDisabled()) return;
        try {
            int port = Authentication.parsePort(view.port.getText());
            String host = view.host.getText().trim();
            Authentication.validate(host, port, "validUser", "validPassword");
            view.showConnectionChecking(true);
            view.serverStatus.setText("Đang kiểm tra kết nối…");
            view.serverStatus.getStyleClass().setAll("server-status", "server-status-checking");
            view.serverStatus.setVisible(true);
            view.serverStatus.setManaged(true);
            CompletableFuture.runAsync(() -> {
                try (java.net.Socket socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress(host, port), 3000);
                    Platform.runLater(() -> {
                        view.showConnectionChecking(false);
                        view.serverStatus.setText("● Kết nối thành công");
                        view.serverStatus.getStyleClass().setAll("server-status", "server-status-ok");
                        view.username.requestFocus();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        view.showConnectionChecking(false);
                        view.serverStatus.setText("● Không thể kết nối tới máy chủ");
                        view.serverStatus.getStyleClass().setAll("server-status", "server-status-fail");
                    });
                }
            });
        } catch (Authentication.ValidationException e) {
            view.showValidationError(e);
        }
    }

    private void connect() {
        if (view.connect.isDisabled()) return;
        view.clearAuthenticationError();
        try {
            int port = Authentication.parsePort(view.port.getText());
            String host = view.host.getText().trim();
            loginName = view.username.getText().trim();
            String password = view.password.getText();
            boolean signUp = view.signUp.isSelected();
            Authentication.validate(host, port, loginName, password);
            if (signUp) Authentication.validateConfirmation(password, view.confirmPassword.getText());
            serverAddress = host + ":" + port;
            conversations.clear(); drafts.clear(); active = null;
            view.message.clear();
            view.showConversation(List.of());
            view.showNotice("");
            view.loginError.setText("");
            loginBusy(true);
            client.connect(host, port, loginName, password, signUp)
                    .whenComplete((unused, error) -> Platform.runLater(() -> {
                        if (error != null) { loginBusy(false); error(error); }
                    }));
        } catch (Authentication.ValidationException e) {
            view.showValidationError(e);
        } catch (IllegalArgumentException e) { error(e); }
    }

    private void loginBusy(boolean busy) {
        view.showAuthenticationBusy(busy);
    }

    private void filterUsers() {
        refreshingList = true;
        String query = view.search.getText().toLowerCase(Locale.ROOT);
        view.users.getItems().setAll(online.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(query)).toList());
        if (active != null && !active.equals(ChatMessage.GROUP)) view.users.getSelectionModel().select(active);
        view.onlineCount.setText(Integer.toString(online.size()));
        refreshingList = false;
    }

    private void select(String conversation) {
        if (!Objects.equals(active, conversation)) {
            if (active != null) drafts.put(active, view.message.getText());
            view.message.setText(drafts.getOrDefault(conversation, ""));
        }
        active = conversation;
        if (ChatMessage.GROUP.equals(active)) view.users.getSelectionModel().clearSelection();
        view.showNotice("");
        render(); update();
    }

    private void render() { view.showConversation(conversations.getOrDefault(active, List.of())); }

    private void update() {
        boolean isGroup = ChatMessage.GROUP.equals(active);
        boolean isGroupJoined = multicastService.isJoined();
        boolean available = connected && active != null && (isGroup ? isGroupJoined : online.contains(active));

        view.groupState(isGroupJoined, joining);
        view.groupAction.setDisable(!connected || joining);
        view.leaveGroupHeader.setDisable(!connected || joining);
        view.send.setDisable(!available || sending);
        view.attach.setDisable(!available || sending);
        view.message.setDisable(!available || sending);
        view.refresh.setDisable(!connected);

        view.configureHeader(isGroup, isGroupJoined, active);
    }

    private void send() {
        if (view.send.isDisabled() || view.message.getText().isBlank()) return;
        String text = view.message.getText().trim();
        String destination = active;
        if (text.isEmpty()) return;

        sending = true;
        update();

        if (ChatMessage.GROUP.equals(destination)) {
            // Gửi tin nhắn nhóm UDP Multicast trực tiếp qua MulticastChatService
            ChatMessage myMsg = new ChatMessage(ChatMessage.GROUP, loginName, text, Instant.now(), true, false);
            List<ChatMessage> history = conversations.computeIfAbsent(ChatMessage.GROUP, ignored -> new ArrayList<>());
            history.add(myMsg);
            if (history.size() > 500) history.removeFirst();
            render();

            drafts.remove(destination);
            view.message.clear();

            CompletableFuture.runAsync(() -> {
                try {
                    multicastService.sendMessage(text);
                } catch (Exception e) {
                    Platform.runLater(() -> error(e));
                } finally {
                    Platform.runLater(() -> {
                        sending = false;
                        update();
                        view.message.requestFocus();
                    });
                }
            });
        } else {
            // Gửi tin nhắn cá nhân 1-1 qua TCP ChatClient
            CompletableFuture<Void> operation = client.sendMessage(destination, text);
            operation.whenComplete((unused, error) -> Platform.runLater(() -> {
                sending = false;
                if (error == null) {
                    drafts.remove(destination);
                    if (Objects.equals(active, destination)) view.message.clear();
                } else error(error);
                update(); view.message.requestFocus();
            }));
        }
    }

    private void report(CompletableFuture<Void> future) {
        future.whenComplete((unused, error) -> { if (error != null) Platform.runLater(() -> error(error)); });
    }

    private void error(Throwable error) {
         String text = Authentication.errorMessage(error);
         view.loginError.setText(text);
         view.showNotice(text);
    }

    @Override public void close() {
        multicastService.close();
        client.close();
    }
}
