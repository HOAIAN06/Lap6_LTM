package com.clientserverchat.client.core;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationTest {
    @Test void validationIdentifiesTheFieldToCorrect() {
        assertEquals(Authentication.Field.PORT, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.parsePort(null)).field());
        assertEquals(Authentication.Field.HOST, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.validate("", 2005, "Alice", "Secret123!")).field());
        assertEquals(Authentication.Field.USERNAME, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.validate("localhost", 2005, "has space", "Secret123!")).field());
        assertEquals(Authentication.Field.PASSWORD, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.validate("localhost", 2005, "Alice", "short")).field());
        assertEquals(Authentication.Field.CONFIRMATION, assertThrows(Authentication.ValidationException.class,
                () -> Authentication.validateConfirmation("Secret123!", "different")).field());
    }

    @Test void acceptsUnicodeAccountsAndPreservesPasswordWhitespace() {
        assertDoesNotThrow(() -> Authentication.validate("127.0.0.1", 2005, "Minh_Đức-01", " pass123 "));
        assertDoesNotThrow(() -> Authentication.validateConfirmation(" pass123 ", " pass123 "));
        assertThrows(IllegalArgumentException.class, () -> Authentication.validateConfirmation(" pass123 ", "pass123"));
        assertEquals(2005, Authentication.parsePort(" 2005 "));
    }

    @Test void rejectsInvalidFormBeforeOpeningAConnection() {
        for (String host : new String[]{"", "0.0.0.0", "::", "http://localhost", "127.0.0.1/path", "bad host"}) {
            assertThrows(IllegalArgumentException.class, () -> Authentication.validate(host, 2005, "Alice", "Secret123!"));
        }
        for (String port : new String[]{"", "abc", "0", "65536", "999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> Authentication.parsePort(port));
        }
        for (String name : new String[]{"", "has space", "../Alice", "x".repeat(33)}) {
            assertThrows(IllegalArgumentException.class, () -> Authentication.validate("localhost", 2005, name, "Secret123!"));
        }
        for (String password : new String[]{"", "12345", "      ", "x".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> Authentication.validate("localhost", 2005, "Alice", password));
        }
        assertThrows(IllegalArgumentException.class, () -> Authentication.validateConfirmation("Secret123!", ""));
        assertThrows(IllegalArgumentException.class, () -> Authentication.validateConfirmation("Secret123!", "Wrong123!"));
    }

     @Test void translatesNetworkFailuresAndPreservesAccountErrors() {
         String refused = Authentication.errorMessage(new CompletionException(new ConnectException("Connection refused: getsockopt")));
         assertTrue(refused.contains("khởi động server"));
         assertFalse(refused.contains("getsockopt"));
         assertTrue(Authentication.errorMessage(new UnknownHostException("badhost")).contains("Không tìm thấy server"));
         assertTrue(Authentication.errorMessage(new SocketTimeoutException()).contains("phản hồi quá lâu"));
         String accountError = Authentication.errorMessage(new CompletionException(new IOException("Tài khoản đã tồn tại")));
         assertTrue(accountError.contains("Tài khoản đã tồn tại"), "Account error was: " + accountError);
     }
}
