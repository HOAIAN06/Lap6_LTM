/*
 * File: Main.java
 * Vai trò: Điểm vào chung của toàn bộ chương trình.
 * Mục đích: Đọc tham số dòng lệnh để chọn chạy giao diện server hoặc client.
 * Phương thức chính:
 * - main(): chọn vai trò "server" hoặc "client".
 */
package com.clientserverchat;

import com.clientserverchat.client.ClientLauncher;
import com.clientserverchat.server.ServerLauncher;
import java.util.Arrays;

/** Điểm vào chung; truyền tham số đầu tiên để chọn server hoặc client. */
public final class Main {
    /** Không cho tạo object Main vì lớp này chỉ chứa hàm main. */
    private Main() {}

    /** Chạy server nếu tham số là "server", chạy client nếu không truyền hoặc truyền "client". */
    public static void main(String[] args) {
        String role = args.length == 0 ? "client" : args[0];
        String[] applicationArgs = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (role.toLowerCase(java.util.Locale.ROOT)) {
            case "server" -> ServerLauncher.main(applicationArgs);
            case "client" -> ClientLauncher.main(applicationArgs);
            default -> throw new IllegalArgumentException("Cách chạy: Main [server|client]. Mặc định: client.");
        }
    }
}
