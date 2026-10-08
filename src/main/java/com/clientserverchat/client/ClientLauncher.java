/*
 * File: ClientLauncher.java
 * Vai trò: Lớp launcher cho client.
 * Mục đích: Giúp file JAR chạy JavaFX client ổn định khi JavaFX nằm trên classpath.
 * Phương thức chính:
 * - main(): chuyển quyền chạy sang ClientApp.
 */
package com.clientserverchat.client;

/** Launcher mỏng để khởi động ClientApp. */
public final class ClientLauncher {
    /** Gọi ClientApp.main để mở giao diện client. */
    public static void main(String[] args) { ClientApp.main(args); }
}
