/*
 * File: AuthenticationTest.java
 * Vai trò: Test kiểm tra form đăng nhập/đăng ký phía client.
 * Mục đích: Đảm bảo validate đúng field lỗi và thông báo lỗi mạng dễ hiểu.
 */
package com.clientserverchat.client.core;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationTest {
    /** Kiểm tra mỗi lỗi validate trả đúng Field để UI focus/highlight đúng ô. */
    @Test void validationIdentifiesTheFieldToCorrect() {
        assertEquals(Authentication.Field.CONG, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.docCong(null)).field());
        assertEquals(Authentication.Field.MAY_CHU, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.kiemTraDangNhap("", 2005, "Alice", "Secret123!")).field());
        assertEquals(Authentication.Field.TAI_KHOAN, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.kiemTraDangNhap("localhost", 2005, "has space", "Secret123!")).field());
        assertEquals(Authentication.Field.MAT_KHAU, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.kiemTraDangNhap("localhost", 2005, "Alice", "short")).field());
        assertEquals(Authentication.Field.NHAP_LAI_MAT_KHAU, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.kiemTraNhapLaiMatKhau("Secret123!", "different")).field());
    }

    /** Kiểm tra tên Unicode hợp lệ và mật khẩu được giữ nguyên khoảng trắng đầu/cuối. */
    @Test void acceptsUnicodeAccountsAndPreservesPasswordWhitespace() {
        assertDoesNotThrow(() -> Authentication.kiemTraDangNhap("127.0.0.1", 2005, "Minh_Đức-01", " pass123 "));
        assertDoesNotThrow(() -> Authentication.kiemTraNhapLaiMatKhau(" pass123 ", " pass123 "));
        assertThrows(IllegalArgumentException.class, () -> Authentication.kiemTraNhapLaiMatKhau(" pass123 ", "pass123"));
        assertEquals(2005, Authentication.docCong(" 2005 "));
    }

    /** Kiểm tra các dữ liệu sai bị chặn trước khi mở socket TCP. */
    @Test void rejectsInvalidFormBeforeOpeningAConnection() {
        for (String host : new String[]{"", "0.0.0.0", "::", "http://localhost", "127.0.0.1/path", "bad host"}) {
            assertThrows(IllegalArgumentException.class, () -> Authentication.kiemTraDangNhap(host, 2005, "Alice", "Secret123!"));
        }
        for (String port : new String[]{"", "abc", "0", "65536", "999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> Authentication.docCong(port));
        }
        for (String name : new String[]{"", "has space", "../Alice", "x".repeat(33)}) {
            assertThrows(IllegalArgumentException.class, () -> Authentication.kiemTraDangNhap("localhost", 2005, name, "Secret123!"));
        }
        for (String password : new String[]{"", "12345", "      ", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> Authentication.kiemTraDangNhap("localhost", 2005, "Alice", password));
        }
        assertThrows(IllegalArgumentException.class, () -> Authentication.kiemTraNhapLaiMatKhau("Secret123!", ""));
        assertThrows(IllegalArgumentException.class, () -> Authentication.kiemTraNhapLaiMatKhau("Secret123!", "Wrong123!"));
    }

     /** Kiểm tra lỗi mạng được đổi thành thông báo thân thiện và lỗi tài khoản vẫn giữ nội dung server. */
     @Test void translatesNetworkFailuresAndPreservesAccountErrors() {
         String refused = Authentication.thongBaoLoi(new CompletionException(new ConnectException("Connection refused: getsockopt")));
         assertTrue(refused.contains("khởi động server"));
         assertFalse(refused.contains("getsockopt"));
         assertTrue(Authentication.thongBaoLoi(new UnknownHostException("badhost")).contains("Không tìm thấy server"));
         assertTrue(Authentication.thongBaoLoi(new SocketTimeoutException()).contains("phản hồi quá lâu"));
         String accountError = Authentication.thongBaoLoi(new CompletionException(new IOException("Tài khoản đã tồn tại")));
         assertTrue(accountError.contains("Tài khoản đã tồn tại"), "Account error was: " + accountError);
     }
}
