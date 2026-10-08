/*
 * File: ServerLauncher.java
 * Vai trò: Lớp launcher cho server.
 * Mục đích: Giúp file JAR chạy JavaFX server ổn định khi JavaFX nằm trên classpath.
 * Phương thức chính:
 * - main(): chuyển quyền chạy sang ServerApp.
 */
package com.clientserverchat.server;

/** Launcher mỏng để khởi động ServerApp. */
public final class ServerLauncher {
    /** Gọi ServerApp.main để mở giao diện quản lý server. */
    public static void main(String[] args) { ServerApp.main(args); }
}
