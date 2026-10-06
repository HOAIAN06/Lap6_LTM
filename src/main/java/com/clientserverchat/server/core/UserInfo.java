package com.clientserverchat.server.core;

import java.time.Instant;

/** Local administration snapshot; the password is available only in the current process. */
public record UserInfo(String username, String ip, Instant loginTime, Instant logoutTime, boolean online,
                       String password) {
    public String passwordDisplay() { return password == null ? "Chưa có trong phiên" : password; }
    public String status() { return online ? "Online" : "Offline"; }

    @Override public String toString() {
        return "UserInfo[username=" + username + ", ip=" + ip + ", loginTime=" + loginTime
                + ", logoutTime=" + logoutTime + ", online=" + online + "]";
    }
}
