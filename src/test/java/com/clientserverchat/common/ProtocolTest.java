/*
 * File: ProtocolTest.java
 * Vai trò: Test giao thức TCP chung.
 * Mục đích: Chứng minh frame nhị phân đọc/ghi đúng và dữ liệu file không lẫn với tin nhắn text.
 */
package com.clientserverchat.common;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    /** Kiểm tra frame bị cắt ngắn và tên file nguy hiểm đều bị từ chối. */
    @Test void rejectsTruncatedFramesAndUnsafeFilenames() throws Exception {
        byte[] encoded = Protocol.encode(new Protocol.Packet("FILE", 4, List.of("Bob", "test.bin"), new byte[]{0, 1, -1}));
        assertThrows(IOException.class, () -> Protocol.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
        for (String name : List.of("../file", "..\\file", "C:\\file", "..", "a\nb", "a\nb\nc", "file|name")) {
            assertThrows(IOException.class, () -> Protocol.validFilename(name));
        }
        assertEquals("ảnh.png", Protocol.validFilename("ảnh.png"));
    }

    /** Kiểm tra nhiều frame liên tiếp vẫn tách riêng dữ liệu file và tin nhắn. */
    @Test void consecutiveBinaryAndTextFramesRemainSeparate() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        Protocol.write(out, new Protocol.Packet("FILE", 1, List.of("Alice", "data.bin"), new byte[]{0, 1, 124, -1}));
        Protocol.write(out, new Protocol.Packet("MESSAGE", 2, "Alice", "chào | 😀"));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertArrayEquals(new byte[]{0, 1, 124, -1}, Protocol.read(in).data());
        assertEquals("chào | 😀", Protocol.read(in).field(1));
        assertEquals(0, in.available());
    }
}
