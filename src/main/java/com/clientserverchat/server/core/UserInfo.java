/*
 * File: UserInfo.java
 * Vai trò: Model dữ liệu hiển thị trên bảng server.
 * Mục đích: Gom thông tin tài khoản, IP, giờ vào/ra, trạng thái và mật khẩu rõ.
 * Phương thức chính:
 * - passwordDisplay(): trả mật khẩu để hiển thị.
 * - status(): đổi boolean online thành chữ Online/Offline.
 * - toString(): in debug nhưng không in mật khẩu.
 */
package com.clientserverchat.server.core;

import java.time.Instant;

/** Local administration snapshot shown in the server table. */
public record UserInfo(String username, String ip, Instant loginTime, Instant logoutTime, boolean online,
                       String password) {
    /** Trả về mật khẩu rõ để cột MẬT KHẨU của server hiển thị trực tiếp. */
    public String passwordDisplay() { return password; }

    /** Chuyển trạng thái online boolean thành chữ dễ đọc trên giao diện. */
    public String status() { return online ? "Online" : "Offline"; }

    /** In thông tin tài khoản cho debug, cố ý không in mật khẩu. */
    @Override public String toString() {
        return "UserInfo[username=" + username + ", ip=" + ip + ", loginTime=" + loginTime
                + ", logoutTime=" + logoutTime + ", online=" + online + "]";
    }
}
