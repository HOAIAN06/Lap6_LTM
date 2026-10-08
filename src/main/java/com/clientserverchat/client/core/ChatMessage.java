/*
 * File: ChatMessage.java
 * Vai trò: Model dữ liệu tin nhắn phía client.
 * Mục đích: Đóng gói một sự kiện chat để UI hiển thị mà không cần biết giao thức TCP/UDP.
 * Trường dữ liệu chính:
 * - conversation: tên cuộc trò chuyện hoặc phòng nhóm.
 * - sender/content/time: người gửi, nội dung, thời điểm.
 * - outgoing/file: tin do mình gửi hay nhận, là tin chữ hay file.
 */
package com.clientserverchat.client.core;

import java.time.Instant;

/** Sự kiện chat độc lập với giao diện và giao thức mạng. */
public record ChatMessage(String conversation, String sender, String content, Instant time, boolean outgoing, boolean file) {
    /** Mã định danh cố định cho phòng chung multicast. */
    public static final String GROUP = "#multicast";
}
