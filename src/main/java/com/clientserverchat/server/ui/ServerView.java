/*
 * File: ServerView.java
 * Vai trò: Giao diện quản lý server.
 * Mục đích: Dựng màn hình nhập IP/cổng, bảng người dùng, trạng thái server và nhật ký hoạt động.
 * Phương thức chính:
 * - setupTable(): tạo các cột bảng tài khoản.
 * - showUsers(): đổ dữ liệu người dùng lên bảng.
 * - showRunning(): bật/tắt nút theo trạng thái server.
 * - appendLog(): thêm dòng nhật ký hoạt động.
 */
package com.clientserverchat.server.ui;

import com.clientserverchat.server.core.UserInfo;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/** Server controls, account list and activity log. */
public final class ServerView extends BorderPane {
    final TextField ip = new TextField("0.0.0.0");
    final TextField port = new TextField("2005");
    final Button start = button("Khởi động", "primary");
    final Button stop = button("Dừng server", "secondary");
    final TableView<UserInfo> table = new TableView<>();
    final Label status = label("Đã dừng", "badge-neutral");
    final Label notice = label("", "server-notice");
    final TextArea logs = new TextArea();
    private final Label userCount = label("0 tài khoản", "muted");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /** Dựng toàn bộ layout server: header, form IP/cổng, bảng người dùng và nhật ký. */
    public ServerView() {
        getStyleClass().addAll("app-root", "server-root");
        HBox heading = new HBox(12, label("Quản lý server", "page-title"), spacer(), status);
        heading.setAlignment(Pos.CENTER_LEFT);
        ip.setPrefWidth(185);
        ip.setTooltip(new Tooltip("IP trên máy server; 0.0.0.0 để nhận kết nối trên mọi card mạng."));
        port.setPrefWidth(90);
        port.setMaxWidth(90);
        HBox connection = new HBox(12, field("IP server", ip), field("Cổng TCP", port), start, stop);
        connection.setAlignment(Pos.BOTTOM_LEFT);
        connection.getStyleClass().add("padded-card");
        ip.setOnAction(event -> start.fire());
        port.setOnAction(event -> start.fire());

        HBox tableHeader = new HBox(14, label("Người dùng", "section-title"), userCount, spacer());
        tableHeader.setAlignment(Pos.CENTER_LEFT);
        tableHeader.setPadding(new Insets(16));
        setupTable();
        VBox accounts = new VBox(tableHeader, table);
        accounts.getStyleClass().add("card");
        accounts.setMinHeight(265);
        VBox.setVgrow(table, Priority.ALWAYS);

        logs.setEditable(false);
        logs.setWrapText(true);
        logs.setPromptText("Chưa có hoạt động");
        logs.getStyleClass().add("event-log");
        Button clearLogs = button("Xóa nhật ký", "secondary");
        clearLogs.setOnAction(event -> logs.clear());
        HBox logHeader = new HBox(12, label("Nhật ký hoạt động", "section-title"), spacer(), clearLogs);
        logHeader.setAlignment(Pos.CENTER_LEFT);
        VBox logCard = new VBox(10, logHeader, logs);
        logCard.getStyleClass().add("padded-card");
        logCard.setMinHeight(160);
        VBox.setVgrow(logs, Priority.ALWAYS);
        SplitPane panels = new SplitPane(accounts, logCard);
        panels.setOrientation(Orientation.VERTICAL);
        panels.setDividerPositions(0.62);
        panels.getStyleClass().add("server-panels");
        VBox.setVgrow(panels, Priority.ALWAYS);
        notice.setWrapText(true);
        notice.visibleProperty().bind(notice.textProperty().isNotEmpty());
        notice.managedProperty().bind(notice.visibleProperty());
        VBox content = new VBox(14, heading, connection, notice, panels);
        content.setPadding(new Insets(22));
        setCenter(content);
    }

    /** Tạo bảng người dùng gồm tài khoản, mật khẩu, IP, giờ vào/ra và trạng thái. */
    private void setupTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(label("Không có tài khoản", "muted"));
        column("TÀI KHOẢN", UserInfo::username, 140);
        TableColumn<UserInfo, String> password = column("MẬT KHẨU", UserInfo::passwordDisplay, 170);
        password.setCellFactory(ignored -> new TableCell<>() {
            /** Hiển thị mật khẩu rõ và tooltip cùng nội dung cho từng dòng. */
            @Override protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                setTooltip(empty ? null : new Tooltip(text));
            }
        });
        column("ĐỊA CHỈ IP", user -> user.ip().isBlank() ? "—" : user.ip(), 130);
        column("ĐĂNG NHẬP", user -> time(user.loginTime()), 165);
        column("ĐĂNG XUẤT", user -> time(user.logoutTime()), 165);
        TableColumn<UserInfo, String> state = column("TRẠNG THÁI", UserInfo::status, 110);
        state.setCellFactory(ignored -> new TableCell<>() {
            /** Hiển thị trạng thái Online/Offline dưới dạng badge màu. */
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(null);
                setGraphic(empty || value == null ? null : label(value,
                        value.equals("Online") ? "badge-online" : "badge-neutral"));
            }
        });
    }

    /** Tạo một cột TableView đọc dữ liệu từ UserInfo bằng function truyền vào. */
    private TableColumn<UserInfo, String> column(String title, Function<UserInfo, String> value, double width) {
        TableColumn<UserInfo, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.apply(data.getValue())));
        column.setPrefWidth(width);
        column.setMinWidth(90);
        table.getColumns().add(column);
        return column;
    }

    /** Hiển thị toàn bộ danh sách tài khoản lên bảng server. */
    public void showUsers(List<UserInfo> all) {
        userCount.setText(all.size() + " tài khoản");
        table.getItems().setAll(all);
        table.sort();
    }

    /** Cập nhật trạng thái nút Khởi động/Dừng và badge Đang chạy/Đã dừng. */
    public void showRunning(boolean running, boolean busy) {
        start.setDisable(running || busy);
        stop.setDisable(!running || busy);
        ip.setDisable(running || busy);
        port.setDisable(running || busy);
        status.setText(busy ? "Đang xử lý" : running ? "Đang chạy" : "Đã dừng");
        status.getStyleClass().setAll(running ? "badge-online" : "badge-neutral");
    }

    /** Thêm một dòng nhật ký kèm thời gian, tự cắt bớt nếu log quá dài. */
    void appendLog(String text) {
        logs.appendText(TIME.format(Instant.now()) + "  " + text + "\n");
        if (logs.getLength() > 50_000) {
            int end = logs.getText().indexOf('\n', logs.getLength() - 40_000);
            if (end >= 0) logs.deleteText(0, end + 1);
        }
    }

    /** Tạo cụm label + TextField dùng cho IP server và cổng TCP. */
    private static VBox field(String title, TextField input) {
        Label caption = label(title, "field-label");
        caption.setLabelFor(input);
        return new VBox(6, caption, input);
    }

    /** Format Instant thành chuỗi ngày giờ, null thì hiển thị dấu gạch. */
    private static String time(Instant instant) { return instant == null ? "—" : TIME.format(instant); }

    /** Tạo khoảng co giãn trong HBox để đẩy nội dung sang hai bên. */
    private static Region spacer() { Region region = new Region(); HBox.setHgrow(region, Priority.ALWAYS); return region; }

    /** Tạo Label kèm style class CSS. */
    private static Label label(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }

    /** Tạo Button kèm style class CSS. */
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
}
