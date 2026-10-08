/*
 * File: MulticastChatServiceTest.java
 * Vai trò: Test chức năng UDP multicast và file nhóm.
 * Mục đích: Kiểm tra join/leave phòng, gửi tin nhóm, chống trùng loopback và tải file nhóm.
 */
package com.clientserverchat.client.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.*;
import java.util.Random;
import org.junit.jupiter.api.io.TempDir;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class MulticastChatServiceTest {
    @TempDir Path directory;
    private static final String TEST_GROUP = "230.0.0.1";
    private static final int TEST_PORT = 5005;

    private final List<MulticastChatService> services = new ArrayList<>();

    /** Đóng toàn bộ service multicast sau mỗi test để giải phóng port UDP/TCP. */
    @AfterEach
    void tearDown() {
        for (MulticastChatService svc : services) {
            try {
                svc.close();
            } catch (Exception ignored) {}
        }
        services.clear();
    }

    /** Tạo service multicast dùng group/port test và lưu lại để dọn sau test. */
    private MulticastChatService createService() {
        MulticastChatService svc = new MulticastChatService(TEST_GROUP, TEST_PORT);
        services.add(svc);
        return svc;
    }

    /** Kiểm tra file nhóm giữ nguyên byte, chỉ client đã tham gia phòng mới nhận được. */
    @Test
    void groupFilesPreserveBytesAndOnlyReachJoinedClients() throws Exception {
        MulticastChatService alice = createService(), bob = createService(), outsider = createService();
        BlockingQueue<ChatMessage> inbox = new LinkedBlockingQueue<>(), outside = new LinkedBlockingQueue<>();
        bob.setOnMessageReceived(inbox::add);
        outsider.setOnMessageReceived(outside::add);
        byte[] bytes = new byte[1024 * 1024];
        new Random(42).nextBytes(bytes);
        Path source = Files.write(directory.resolve("tài-liệu.bin"), bytes);
        assertThrows(java.io.IOException.class, () -> outsider.guiTepNhom(source.toFile()));
        bob.thamGiaPhongMulticast("FileTestBob"); alice.thamGiaPhongMulticast("FileTestAlice");
        alice.guiTepNhom(source.toFile());
        ChatMessage message = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        assertTrue(message.file());
        Path saved = Path.of(message.content().split("\nĐã lưu: ", 2)[1]);
        try { assertArrayEquals(bytes, Files.readAllBytes(saved)); }
        finally { Files.deleteIfExists(saved); }
        assertNull(outside.poll(100, TimeUnit.MILLISECONDS));
        bob.roiPhongMulticast();
        alice.guiTepNhom(source.toFile());
        assertNull(inbox.poll(200, TimeUnit.MILLISECONDS));
        bob.thamGiaPhongMulticast("FileTestBob");
        Path empty = Files.createFile(directory.resolve("empty.txt"));
        alice.guiTepNhom(empty.toFile());
        message = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(message);
        saved = Path.of(message.content().split("\nĐã lưu: ", 2)[1]);
        try { assertEquals(0, Files.size(saved)); }
        finally { Files.deleteIfExists(saved); }
    }

    /** Kiểm tra trạng thái tham gia/rời phòng multicast cơ bản. */
    @Test
    void testJoinAndLeaveGroup() throws Exception {
        MulticastChatService service = createService();
        assertFalse(service.daThamGia());

        service.thamGiaPhongMulticast("Alice");
        assertTrue(service.daThamGia());

        service.roiPhongMulticast();
        assertFalse(service.daThamGia());
    }

    /** Kiểm tra hai client trong cùng group nhận được tin nhắn và thông báo JOIN/LEAVE. */
    @Test
    void testMulticastMessageExchangeBetweenClients() throws Exception {
        MulticastChatService alice = createService();
        MulticastChatService bob = createService();

        BlockingQueue<ChatMessage> bobReceived = new LinkedBlockingQueue<>();
        BlockingQueue<String> bobNotices = new LinkedBlockingQueue<>();

        bob.setOnMessageReceived(bobReceived::add);
        bob.setOnSystemNotice(bobNotices::add);

        // Bob joins first
        bob.thamGiaPhongMulticast("Bob");

        // Alice joins (Bob should receive JOIN event)
        alice.thamGiaPhongMulticast("Alice");

        String joinNotice = bobNotices.poll(3, TimeUnit.SECONDS);
        assertNotNull(joinNotice);
        assertTrue(joinNotice.contains("Alice") && joinNotice.contains("tham gia"));

        // Alice sends a multicast message
        alice.guiTinNhanNhom("Xin chào từ Alice");

        ChatMessage received = bobReceived.poll(3, TimeUnit.SECONDS);
        assertNotNull(received, "Bob phải nhận được tin nhắn multicast từ Alice");
        assertEquals("Alice", received.sender());
        assertEquals("Xin chào từ Alice", received.content());
        assertFalse(received.outgoing());

        // Alice leaves (Bob should receive LEAVE event)
        alice.roiPhongMulticast();
        String leaveNotice = bobNotices.poll(3, TimeUnit.SECONDS);
        assertNotNull(leaveNotice);
        assertTrue(leaveNotice.contains("Alice") && leaveNotice.contains("rời phòng"));
    }

    /** Kiểm tra người gửi không hiển thị trùng tin do packet loopback của chính mình. */
    @Test
    void testSenderDoesNotReceiveDuplicate() throws Exception {
        MulticastChatService alice = createService();
        BlockingQueue<ChatMessage> aliceReceived = new LinkedBlockingQueue<>();
        alice.setOnMessageReceived(aliceReceived::add);

        alice.thamGiaPhongMulticast("Alice");
        alice.guiTinNhanNhom("Tin nhắn kiểm tra chống trùng");

        // Vì loopback packet bị sentMessageSignatures lọc bỏ, aliceReceived không nhận lại packet này
        ChatMessage duplicate = aliceReceived.poll(500, TimeUnit.MILLISECONDS);
        assertNull(duplicate, "Gói tin của chính người gửi dội về phải được lọc bỏ, không trùng lặp");
    }
}
