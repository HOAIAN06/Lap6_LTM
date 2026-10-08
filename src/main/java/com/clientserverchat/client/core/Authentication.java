/*
 * File: Authentication.java
 * Vai trò: Kiểm tra form đăng nhập/đăng ký phía client.
 * Mục đích: Bắt lỗi nhập liệu sớm và đổi lỗi mạng thành thông báo dễ hiểu cho người dùng.
 * Phương thức chính:
 * - docCong(): đọc và kiểm tra cổng TCP.
 * - kiemTraDangNhap(): kiểm tra IP server, tài khoản, mật khẩu.
 * - kiemTraNhapLaiMatKhau(): kiểm tra mật khẩu xác nhận khi đăng ký.
 * - thongBaoLoi(): đổi exception kỹ thuật thành câu báo lỗi trên giao diện.
 */
package com.clientserverchat.client.core;

import java.io.EOFException;
import java.net.*;
import java.util.concurrent.TimeoutException;

/** Client-side checks and actionable authentication errors; server validation remains authoritative. */
public final class Authentication {
    /** Không cho tạo object Authentication vì lớp này chỉ chứa helper static. */
    private Authentication() {}

    /** Tên các ô nhập liệu để UI biết ô nào cần highlight khi có lỗi. */
    public enum Field { MAY_CHU, CONG, TAI_KHOAN, MAT_KHAU, NHAP_LAI_MAT_KHAU }

    /** Lỗi kiểm tra form, kèm field để ClientView tô đỏ đúng ô nhập. */
    public static final class ValidationException extends IllegalArgumentException {
        private final Field field;

        /** Tạo lỗi validate cho một ô cụ thể trên form. */
        private ValidationException(Field field, String message) {
            super(message);
            this.field = field;
        }

        /** Trả về field gây lỗi để giao diện focus/highlight. */
        public Field field() { return field; }
    }

    /** Đọc chuỗi cổng TCP thành số và chỉ chấp nhận khoảng 1-65535. */
    public static int docCong(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.trim());
            if (port >= 1 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {}
        throw new ValidationException(Field.CONG, "Cổng TCP phải là số từ 1 đến 65535.");
    }

    /** Kiểm tra dữ liệu đăng nhập/đăng ký trước khi client mở kết nối TCP. */
    public static void kiemTraDangNhap(String host, int port, String username, String password) {
        if (host == null || host.isBlank()) throw new ValidationException(Field.MAY_CHU, "Vui lòng nhập IP server.");
        if (host.chars().anyMatch(Character::isWhitespace) || host.contains("://") || host.contains("/")) {
            throw new ValidationException(Field.MAY_CHU, "Nhập IP hoặc tên máy server, không nhập đường dẫn web.");
        }
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("[::]")) {
            throw new ValidationException(Field.MAY_CHU, "Dùng 127.0.0.1 nếu server cùng máy, hoặc IP LAN của máy server.");
        }
        if (port < 1 || port > 65535) throw new ValidationException(Field.CONG, "Cổng TCP phải là số từ 1 đến 65535.");
        if (username == null || !username.matches("[\\p{L}\\p{N}_-]{1,32}")) {
            throw new ValidationException(Field.TAI_KHOAN, "Tài khoản cần 1–32 chữ cái, chữ số, dấu _ hoặc -; không có khoảng trắng.");
        }
        if (password == null || password.isBlank() || password.length() < 6 || password.length() > 128) {
            throw new ValidationException(Field.MAT_KHAU, "Mật khẩu cần từ 6 đến 128 ký tự.");
        }
    }

    /** Kiểm tra ô nhập lại mật khẩu khi người dùng chọn Đăng ký. */
    public static void kiemTraNhapLaiMatKhau(String password, String confirmation) {
        if (confirmation == null || confirmation.isEmpty()) {
            throw new ValidationException(Field.NHAP_LAI_MAT_KHAU, "Vui lòng nhập lại mật khẩu.");
        }
        if (!confirmation.equals(password)) throw new ValidationException(Field.NHAP_LAI_MAT_KHAU, "Mật khẩu xác nhận không khớp.");
    }

    /** Chuyển lỗi mạng/tài khoản thành thông báo ngắn, dễ hiểu trên giao diện client. */
    public static String thongBaoLoi(Throwable error) {
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
