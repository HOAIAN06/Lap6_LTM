package com.clientserverchat.client.core;

import java.io.EOFException;
import java.net.*;
import java.util.concurrent.TimeoutException;

/** Client-side checks and actionable authentication errors; server validation remains authoritative. */
public final class Authentication {
    private Authentication() {}

    public enum Field { HOST, PORT, USERNAME, PASSWORD, CONFIRMATION }

    public static final class ValidationException extends IllegalArgumentException {
        private final Field field;

        private ValidationException(Field field, String message) {
            super(message);
            this.field = field;
        }

        public Field field() { return field; }
    }

    public static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.trim());
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {}
        throw new ValidationException(Field.PORT, "Cổng TCP phải là số từ 1 đến 65535.");
    }

    public static void validate(String host, int port, String username, String password) {
        if (host == null || host.isBlank()) throw new ValidationException(Field.HOST, "Vui lòng nhập IP server.");
        if (host.chars().anyMatch(Character::isWhitespace) || host.contains("://") || host.contains("/")) {
            throw new ValidationException(Field.HOST, "Nhập IP hoặc tên máy server, không nhập đường dẫn web.");
        }
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            throw new ValidationException(Field.HOST, "Dùng 127.0.0.1 nếu server cùng máy, hoặc IP LAN của máy server.");
        }
        if (port < 1 || port > 65535) throw new ValidationException(Field.PORT, "Cổng TCP phải là số từ 1 đến 65535.");
        if (username == null || !username.matches("[\\p{L}\\p{N}_-]{1,32}")) {
            throw new ValidationException(Field.USERNAME, "Tài khoản cần 1–32 chữ cái, chữ số, dấu _ hoặc -; không có khoảng trắng.");
        }
        if (password == null || password.isBlank() || password.length() < 6 || password.length() > 128) {
            throw new ValidationException(Field.PASSWORD, "Mật khẩu cần từ 6 đến 128 ký tự.");
        }
    }

    public static void validateConfirmation(String password, String confirmation) {
        if (confirmation == null || confirmation.isEmpty()) {
            throw new ValidationException(Field.CONFIRMATION, "Vui lòng nhập lại mật khẩu.");
        }
        if (!confirmation.equals(password)) throw new ValidationException(Field.CONFIRMATION, "Mật khẩu xác nhận không khớp.");
    }

    public static String errorMessage(Throwable error) {
         while (error.getCause() != null) error = error.getCause();
         if (error instanceof ConnectException) {
             return "Không kết nối được server. Hãy khởi động server và kiểm tra IP, cổng TCP ở cả hai ứng dụng.";
         }
         if (error instanceof UnknownHostException) return "Không tìm thấy server. Hãy kiểm tra lại IP hoặc tên máy server.";
         if (error instanceof NoRouteToHostException) return "Không truy cập được máy server. Hãy kiểm tra mạng LAN và IP server.";
         if (error instanceof SocketTimeoutException || error instanceof TimeoutException) {
             return "Server phản hồi quá lâu. Hãy kiểm tra kết nối mạng và thử lại.";
         }
         if (error instanceof SocketException || error instanceof EOFException) {
             return "Kết nối tới server đã bị ngắt. Hãy kiểm tra server và thử lại.";
         }
         return error.getMessage() == null || error.getMessage().isBlank()
                 ? "Không thể thực hiện thao tác. Vui lòng thử lại." : error.getMessage();
     }
}
