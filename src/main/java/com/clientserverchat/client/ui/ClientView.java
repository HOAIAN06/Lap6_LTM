/*
 * File: ClientView.java
 * Vai trò: Giao diện client JavaFX.
 * Mục đích: Dựng màn hình đăng nhập/đăng ký, danh sách online, vùng chat, gửi tin và gửi file.
 * Phương thức chính:
 * - createLogin(): tạo form IP/cổng/tài khoản/mật khẩu.
 * - createChat(): tạo màn hình chat sau khi đăng nhập.
 * - showConversation()/addBubble(): hiển thị lịch sử tin nhắn.
 * - groupState()/configureHeader(): cập nhật giao diện phòng multicast.
 */
package com.clientserverchat.client.ui;

import com.clientserverchat.client.core.ChatMessage;
import com.clientserverchat.client.core.Authentication;
import javafx.animation.PauseTransition;
import javafx.css.PseudoClass;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Creates and renders controls only. Network calls and application state belong to ClientController. */
public final class ClientView extends BorderPane {
    final TextField host = new TextField("127.0.0.1");
    final TextField port = new TextField("2005");
    final TextField username = new TextField();
    final TextField password = new TextField();
    final TextField confirmPassword = new TextField();
    final ToggleButton signIn = new ToggleButton("Đăng nhập");
    final ToggleButton signUp = new ToggleButton("Đăng ký");
    final Button connect = button("Đăng nhập", "primary");
    final Button connectServer = button("Kết nối", "auth-connect-button");
    final Label serverStatus = label("", "server-status");
    final Button disconnect = button("Đăng xuất", "sidebar-button");
    final Button refresh = button("⟳", "quiet-button");
    final TextField search = new TextField();
    final ListView<String> users = new ListView<>();
    final Button groupRoom = button("#  Phòng chung", "room-button");
    final Button groupAction = button("Tham gia phòng", "group-join-button");
    final Button leaveGroupHeader = button("Tham gia phòng", "primary");
    final Button backButton = button("← Quay lại", "secondary");
    final TextArea message = new TextArea();
    final Button send = button("Gửi →", "primary");
    final Button attach = button("📎  Tệp", "attach-button");
    final Label notice = label("", "notice");
    final Label loginError = label("", "login-error");
    final Label roomTitle = label("Trò chuyện", "section-title");
    final Label roomStatus = label("", "badge-neutral");
    final Label onlineCount = label("0", "count");
    final ScrollPane messageScroll;
    private final Label identity = label("", "profile-name");
    private final Label identityAvatar = label("?", "avatar");
    private final Label serverAddress = label("", "sidebar-muted");
    private final Label groupDescription = label("Chưa tham gia", "group-membership");
    private final VBox groupCard = new VBox(10);
    private final VBox messages = new VBox(12);
    private final Node loginPage;
    private final Node chatPage;
    private final BooleanProperty authenticationBusy = new SimpleBooleanProperty();
    private final BooleanProperty connectionChecking = new SimpleBooleanProperty();
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    /** Tạo ClientView, dựng sẵn trang login và trang chat rồi hiển thị login trước. */
    public ClientView() {
        getStyleClass().add("app-root");
        messageScroll = new ScrollPane(messages);
        loginPage = createLogin();
        chatPage = createChat();
        showLogin();
    }

    /** Dựng màn hình đăng nhập/đăng ký gồm IP server, cổng TCP, tài khoản và mật khẩu rõ. */
    private Node createLogin() {
        ToggleGroup modes = new ToggleGroup();
        signIn.setToggleGroup(modes);
        signUp.setToggleGroup(modes);
        signIn.setSelected(true);
        modes.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null) modes.selectToggle(before == null ? signIn : before);
        });
        signIn.setMaxWidth(Double.MAX_VALUE);
        signUp.setMaxWidth(Double.MAX_VALUE);
        signIn.setPrefWidth(0);
        signUp.setPrefWidth(0);
        HBox.setHgrow(signIn, Priority.ALWAYS);
        HBox.setHgrow(signUp, Priority.ALWAYS);
        HBox modeBar = new HBox(6, signIn, signUp);
        modeBar.getStyleClass().add("auth-modes");

        SVGPath icon = new SVGPath();
        icon.setContent("M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 9h12v2H6V9zm8 5H6v-2h8v2zm4-6H6V6h12v2z");
        icon.getStyleClass().add("auth-icon");
        StackPane iconBox = new StackPane(icon);
        iconBox.getStyleClass().add("auth-icon-box");

        Label title = label("Server-Client Chat", "auth-title");
        HBox heading = new HBox(14, iconBox, title);
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox.setMargin(heading, new Insets(0, 0, 10, 0));

        host.setPromptText("127.0.0.1");
        host.setMaxWidth(Double.MAX_VALUE);
        port.setPromptText("2005");
        port.setMaxWidth(Double.MAX_VALUE);

        connectServer.setMaxWidth(Double.MAX_VALUE);
        serverStatus.setWrapText(true);
        serverStatus.setVisible(false);
        serverStatus.setManaged(false);

        VBox hostField = field("IP Server", host);
        VBox portField = field("Cổng TCP", port);

        VBox leftPane = new VBox(16, heading, hostField, portField, connectServer, serverStatus);
        leftPane.getStyleClass().add("auth-left-pane");
        leftPane.setPrefWidth(370);
        leftPane.setMinWidth(340);
        leftPane.setMaxWidth(400);

        username.setPromptText("Nhập tên tài khoản");
        username.setMaxWidth(Double.MAX_VALUE);
        password.setPromptText("Nhập mật khẩu");
        password.setMaxWidth(Double.MAX_VALUE);
        confirmPassword.setPromptText("Nhập lại mật khẩu");
        confirmPassword.setMaxWidth(Double.MAX_VALUE);

        VBox confirmation = field("Xác nhận mật khẩu", confirmPassword);
        confirmation.visibleProperty().bind(signUp.selectedProperty());
        confirmation.managedProperty().bind(confirmation.visibleProperty());

        connect.setMaxWidth(Double.MAX_VALUE);
        ProgressIndicator progress = new ProgressIndicator();
        progress.setPrefSize(20, 20);
        progress.setMaxSize(20, 20);
        progress.visibleProperty().bind(authenticationBusy);
        progress.managedProperty().bind(progress.visibleProperty());
        connect.setGraphic(progress);
        connect.textProperty().bind(Bindings.when(authenticationBusy)
                .then(Bindings.when(signUp.selectedProperty()).then("Đang đăng ký…").otherwise("Đang đăng nhập…"))
                .otherwise(Bindings.when(signUp.selectedProperty()).then("Đăng ký").otherwise("Đăng nhập")));
        for (Control control : List.of(username, password, confirmPassword, signIn, signUp)) {
            control.disableProperty().bind(authenticationBusy);
        }
        for (Control control : List.of(host, port, connect, connectServer)) {
            control.disableProperty().bind(authenticationBusy.or(connectionChecking));
        }
        connectServer.textProperty().bind(Bindings.when(connectionChecking).then("Đang kết nối…").otherwise("Kết nối"));
        loginError.setWrapText(true);
        loginError.setMinHeight(Region.USE_PREF_SIZE);
        loginError.visibleProperty().bind(loginError.textProperty().isNotEmpty());
        loginError.managedProperty().bind(loginError.visibleProperty());

        VBox.setMargin(modeBar, new Insets(0, 0, 6, 0));
        VBox rightPane = new VBox(18, modeBar,
                field("Tài khoản", username), field("Mật khẩu", password), confirmation,
                loginError, connect);
        rightPane.getStyleClass().add("auth-right-pane");
        rightPane.setPrefWidth(470);
        rightPane.setMinWidth(420);
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        HBox form = new HBox(0, leftPane, rightPane);
        form.getStyleClass().add("auth-card");
        form.setMaxWidth(860);
        form.setMinWidth(0);
        form.setMinHeight(Region.USE_PREF_SIZE);
        form.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane content = new StackPane(form);
        content.getStyleClass().add("auth-background");
        content.setPadding(new Insets(32));
        ScrollPane page = new ScrollPane(content);
        page.getStyleClass().add("auth-scroll");
        page.setFitToWidth(true);
        page.setFitToHeight(true);
        page.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        host.textProperty().addListener((observable, before, after) -> {
            clearAuthenticationError();
            serverStatus.setVisible(false);
            serverStatus.setManaged(false);
        });
        port.textProperty().addListener((observable, before, after) -> {
            clearAuthenticationError();
            serverStatus.setVisible(false);
            serverStatus.setManaged(false);
        });
        for (TextInputControl input : List.of(username, password, confirmPassword)) {
            input.textProperty().addListener((observable, before, after) -> clearAuthenticationError());
        }
        return page;
    }

    /** Bật/tắt trạng thái đang đăng nhập/đăng ký để khóa form và hiện progress. */
    void showAuthenticationBusy(boolean busy) { authenticationBusy.set(busy); }

    /** Bật/tắt trạng thái đang kiểm tra kết nối server. */
    void showConnectionChecking(boolean busy) { connectionChecking.set(busy); }

    /** Gom các ô nhập ở form login để xóa lỗi/highlight hàng loạt. */
    private List<TextInputControl> authenticationInputs() {
        return List.of(host, port, username, password, confirmPassword);
    }

    /** Xóa thông báo lỗi và bỏ viền đỏ trên toàn bộ input đăng nhập. */
    void clearAuthenticationError() {
        loginError.setText("");
        authenticationInputs().forEach(input -> input.pseudoClassStateChanged(INVALID, false));
    }

    /** Hiển thị lỗi validate đúng ô nhập liệu cần sửa. */
    void showValidationError(Authentication.ValidationException error) {
        clearAuthenticationError();
        TextInputControl input = switch (error.field()) {
            case MAY_CHU -> host;
            case CONG -> port;
            case TAI_KHOAN -> username;
            case MAT_KHAU -> password;
            case NHAP_LAI_MAT_KHAU -> confirmPassword;
        };
        input.pseudoClassStateChanged(INVALID, true);
        loginError.setText(error.getMessage());
        input.requestFocus();
    }

    /** Dựng màn hình chat gồm sidebar người dùng, phòng chung, vùng tin nhắn và composer. */
    private Node createChat() {
        VBox sidebar = new VBox(16);
        sidebar.getStyleClass().add("chat-sidebar");
        sidebar.setPrefWidth(270);
        sidebar.setMinWidth(250);

        Label logo = label("LAN CHAT", "brand-title");
        HBox profile = new HBox(12, identityAvatar, new VBox(3, identity, serverAddress));
        profile.setAlignment(Pos.CENTER_LEFT);

        search.setPromptText("Tìm kiếm...");
        search.getStyleClass().add("sidebar-search");

        HBox listHeader = new HBox(8, label("TRỰC TUYẾN", "eyebrow-light"), onlineCount, spacer(), refresh);
        listHeader.setAlignment(Pos.CENTER_LEFT);

        users.getStyleClass().add("people-list");
        users.setPlaceholder(label("Chưa có ai trực tuyến", "sidebar-muted"));
        users.setCellFactory(ignored -> new ListCell<>() {
            /** Vẽ từng dòng người dùng online trong sidebar. */
            @Override protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                setText(null);
                if (empty || name == null) { setGraphic(null); return; }
                HBox row = new HBox(12, avatar(name), label(name, "person-name"), spacer(), label("●", "online-dot"));
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        VBox.setVgrow(users, Priority.ALWAYS);

        groupRoom.setMaxWidth(Double.MAX_VALUE);
        groupRoom.setAlignment(Pos.CENTER_LEFT);
        groupAction.setMaxWidth(Double.MAX_VALUE);
        groupCard.getChildren().addAll(groupRoom, groupDescription, groupAction);
        groupCard.getStyleClass().add("group-card");

        disconnect.setMaxWidth(Double.MAX_VALUE);
        sidebar.getChildren().addAll(logo, profile, search, listHeader, users, groupCard, disconnect);


        leaveGroupHeader.setVisible(false);
        leaveGroupHeader.setManaged(false);
        backButton.setVisible(false);
        backButton.setManaged(false);

        HBox header = new HBox(10, backButton, roomTitle, spacer(), roomStatus, leaveGroupHeader);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("chat-header");

        messages.setPadding(new Insets(20));
        messages.setMaxWidth(920);
        messages.setFillWidth(true);
        messageScroll.setFitToWidth(true);
        messageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messageScroll.getStyleClass().add("message-scroll");

        message.setPromptText("Nhập tin nhắn...");
        message.setWrapText(true);
        message.setPrefRowCount(1);
        message.getStyleClass().add("composer-input");
        message.setPrefHeight(46);
        message.setMinHeight(46);
        message.setMaxHeight(100);
        HBox.setHgrow(message, Priority.ALWAYS);

        HBox composer = new HBox(10, attach, message, send);
        composer.setAlignment(Pos.CENTER);
        composer.getStyleClass().add("composer");

        notice.setWrapText(true);
        notice.setVisible(false);
        notice.setManaged(false);

        VBox bottom = new VBox(notice, composer);
        BorderPane conversation = new BorderPane(messageScroll, header, null, bottom, null);
        BorderPane page = new BorderPane(conversation, null, null, null, sidebar);
        page.getStyleClass().add("chat-page");
        showConversation(List.of());
        return page;
    }

    /** Chuyển về trang login. */
    public void showLogin() { setCenter(loginPage); }

    /** Chuyển sang trang chat và hiển thị danh tính người dùng đang đăng nhập. */
    public void showChat(String name, String server) {
        identity.setText(name);
        identityAvatar.setText(initials(name));
        serverAddress.setText(server);
        setCenter(chatPage);
    }

    /** Vẽ lại toàn bộ lịch sử tin nhắn của cuộc trò chuyện đang chọn. */
    public void showConversation(List<ChatMessage> history) {
        messages.getChildren().clear();
        if (history.isEmpty()) {
            SVGPath chatIcon = new SVGPath();
            chatIcon.setContent("M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 9h12v2H6V9zm8 5H6v-2h8v2zm4-6H6V6h12v2z");
            chatIcon.getStyleClass().add("empty-icon");
            Label emptyText = label("Chưa có tin nhắn nào", "empty-description");
            VBox empty = new VBox(12, chatIcon, emptyText);
            empty.setAlignment(Pos.CENTER);
            empty.setPadding(new Insets(140, 20, 90, 20));
            messages.getChildren().add(empty);
        } else {
            history.forEach(this::addBubble);
        }
        javafx.application.Platform.runLater(() -> messageScroll.setVvalue(1));
    }

    /** Thêm một bubble tin nhắn hoặc file vào vùng chat. */
    private void addBubble(ChatMessage item) {
        if ("Hệ thống".equals(item.sender())) {
            Label systemNotice = label(item.content(), "system-notice-bubble");
            systemNotice.setWrapText(true);
            HBox row = new HBox(systemNotice);
            row.setAlignment(Pos.CENTER);
            row.setPadding(new Insets(4, 0, 4, 0));
            messages.getChildren().add(row);
            return;
        }

        Node contentNode;
        if (item.file()) {
            contentNode = createFileCard(item);
        } else {
            Label textLabel = label(item.content(), "bubble-text");
            textLabel.setWrapText(true);
            textLabel.maxWidthProperty().bind(messageScroll.widthProperty().multiply(0.65).subtract(40));
            contentNode = textLabel;
        }

        Label authorLabel = label(item.outgoing() ? "Bạn" : item.sender(), "bubble-author");
        VBox bubble = new VBox(5, authorLabel, contentNode);
        bubble.getStyleClass().add(item.outgoing() ? "bubble-out" : "bubble-in");

        Label timeLabel = label(TIME.format(item.time()), "message-time");
        VBox block = new VBox(3, bubble, timeLabel);
        block.setAlignment(item.outgoing() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        HBox row = new HBox(8);
        row.setAlignment(item.outgoing() ? Pos.TOP_RIGHT : Pos.TOP_LEFT);
        if (!item.outgoing()) row.getChildren().add(avatar(item.sender()));
        row.getChildren().add(block);
        messages.getChildren().add(row);
    }

    /** Tạo card file trong khung chat, có nút mở file/thư mục cho file nhận được. */
    private Node createFileCard(ChatMessage item) {
        String fileName;
        String filePath = null;
        if (item.content().contains("\nĐã lưu: ")) {
            String[] parts = item.content().split("\nĐã lưu: ", 2);
            fileName = parts[0].trim();
            filePath = parts.length > 1 ? parts[1].trim() : null;
        } else {
            fileName = item.content().trim();
        }

        Label fileIcon = label("📄", "file-icon");
        Label nameLabel = label(fileName, "file-name");
        nameLabel.setWrapText(true);

        VBox fileInfo = new VBox(2, nameLabel);
        if (item.outgoing()) {
            Label statusLabel = label("✓ Đã gửi", "file-status-out");
            fileInfo.getChildren().add(statusLabel);
        }

        HBox topRow = new HBox(10, fileIcon, fileInfo);
        topRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, topRow);
        card.getStyleClass().add("file-card");
        card.setMinWidth(220);
        card.setMaxWidth(360);

        if (!item.outgoing() && filePath != null) {
            final String path = filePath;
            Button openBtn = button("Mở tệp", "file-action-btn");
            openBtn.setOnAction(e -> openFile(path));

            Button folderBtn = button("Thư mục", "file-action-btn");
            folderBtn.setOnAction(e -> openFolder(path));

            HBox actions = new HBox(8, openBtn, folderBtn);
            actions.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(actions);
        }

        return card;
    }

    /** Mở file đã nhận bằng ứng dụng mặc định của hệ điều hành. */
    private void openFile(String path) {
        try {
            File f = new File(path);
            if (f.exists() && java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(f);
            }
        } catch (Exception ex) {
            showNotice("Không thể mở tệp: " + ex.getMessage());
        }
    }

    /** Mở thư mục chứa file đã nhận. */
    private void openFolder(String path) {
        try {
            File f = new File(path);
            if (f.exists() && java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(f.getParentFile());
            }
        } catch (Exception ex) {
            showNotice("Không thể mở thư mục: " + ex.getMessage());
        }
    }

    /** Cập nhật trạng thái nút Tham gia/Rời phòng và mô tả phòng multicast. */
    void groupState(boolean joined, boolean busy) {
        groupDescription.setText(busy ? "Đang xử lý…" : joined ? "● Đã tham gia" : "○ Chưa tham gia");
        groupCard.pseudoClassStateChanged(PseudoClass.getPseudoClass("joined"), joined);
        String action = busy ? "Đang xử lý…" : joined ? "Rời phòng" : "Tham gia phòng";
        groupAction.setText(action);
        leaveGroupHeader.setText(action);
        groupAction.getStyleClass().setAll("button", joined ? "group-leave-button" : "group-join-button");
        leaveGroupHeader.getStyleClass().setAll("button", joined ? "secondary" : "primary");
    }

    /** Cập nhật tiêu đề phòng: phòng chung multicast hoặc chat riêng với người dùng. */
    void configureHeader(boolean isGroup, boolean isJoined, String activeName) {
        if (isGroup) {
            roomTitle.setText("#  Phòng chung");
            message.setPromptText(isJoined ? "Nhắn vào phòng chung..." : "Tham gia phòng để gửi và nhận tin nhắn");
            roomStatus.setText(isJoined ? "● Đã tham gia" : "○ Chưa tham gia");
            roomStatus.getStyleClass().setAll(isJoined ? "badge-online" : "badge-neutral");
            roomStatus.setVisible(true);
            leaveGroupHeader.setVisible(true);
            leaveGroupHeader.setManaged(true);
            backButton.setVisible(true);
            backButton.setManaged(true);
        } else {
            message.setPromptText("Nhập tin nhắn...");
            roomStatus.setVisible(false);
            roomTitle.setText(activeName == null ? "Chưa chọn người dùng" : activeName);
            leaveGroupHeader.setVisible(false);
            leaveGroupHeader.setManaged(false);
            backButton.setVisible(false);
            backButton.setManaged(false);
        }
    }

    /** Hiển thị thông báo ngắn dưới vùng chat rồi tự ẩn sau vài giây. */
    void showNotice(String text) {
        notice.setText(text);
        boolean show = !text.isBlank();
        notice.setVisible(show);
        notice.setManaged(show);
        if (show) {
            PauseTransition delay = new PauseTransition(Duration.seconds(4));
            delay.setOnFinished(e -> {
                notice.setVisible(false);
                notice.setManaged(false);
            });
            delay.play();
        }
    }

    /** Mở hộp chọn file để gửi, giới hạn logic kiểm tra kích thước nằm ở core. */
    File chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp gửi (tối đa 20 MiB)");
        return chooser.showOpenDialog(getScene().getWindow());
    }

    /** Tạo cụm label + control dùng lại cho các ô nhập liệu. */
    private static VBox field(String title, Node input) {
        Label caption = label(title, "field-label");
        caption.setLabelFor(input);
        return new VBox(8, caption, input);
    }

    /** Tạo avatar chữ cái đầu của tên người dùng. */
    private static Label avatar(String name) { return label(initials(name), "avatar"); }

    /** Lấy ký tự đầu tiên của tên để làm avatar, hỗ trợ Unicode. */
    private static String initials(String name) { return name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(); }

    /** Tạo khoảng co giãn trong HBox. */
    private static Region spacer() { Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS); return space; }

    /** Tạo Label kèm style class CSS. */
    private static Label label(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }

    /** Tạo Button kèm style class CSS. */
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
}
