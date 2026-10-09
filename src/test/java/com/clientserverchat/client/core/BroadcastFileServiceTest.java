package com.clientserverchat.client.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class BroadcastFileServiceTest {
    @TempDir Path directory;

    @Test void filesReachMembersAndOutsidersEvenAfterLeavingGroup() throws Exception {
        InetAddress local = null;
        for (NetworkInterface card : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!card.isUp() || card.isLoopback()) continue;
            for (InterfaceAddress address : card.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) { local = address.getAddress(); break; }
            }
            if (local != null) break;
        }
        assertNotNull(local, "Cần card IPv4 có broadcast để kiểm tra mạng thực");
        int port;
        try (DatagramSocket socket = new DatagramSocket(0)) { port = socket.getLocalPort(); }
        BlockingQueue<ChatMessage> senderMessages = new LinkedBlockingQueue<>();
        BlockingQueue<ChatMessage> memberMessages = new LinkedBlockingQueue<>();
        BlockingQueue<ChatMessage> outsiderMessages = new LinkedBlockingQueue<>();
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        try (BroadcastFileService alice = new BroadcastFileService("BroadcastAlice", local, port, senderMessages::add, errors::add);
             BroadcastFileService bob = new BroadcastFileService("BroadcastBob", local, port, memberMessages::add, errors::add);
             BroadcastFileService outsider = new BroadcastFileService("BroadcastOutside", local, port, outsiderMessages::add, errors::add);
             MulticastChatService group = new MulticastChatService("230.0.0.1", 5006)) {
            group.thamGiaPhongMulticast("BroadcastBob", local);
            byte[] bytes = new byte[1024 * 1024];
            new Random(42).nextBytes(bytes);
            Path source = Files.write(directory.resolve("tài-liệu.bin"), bytes);
            alice.guiTep(source.toFile()); // Người gửi và outsider chưa tham gia nhóm.
            assertReceivedBytes(memberMessages, bytes);
            assertReceivedBytes(outsiderMessages, bytes);
            ChatMessage sent = senderMessages.poll(1, TimeUnit.SECONDS);
            assertNotNull(sent);
            assertTrue(sent.outgoing());
            assertNull(senderMessages.poll(200, TimeUnit.MILLISECONDS), "Không tải lại tệp của chính mình");
            group.roiPhongMulticast();
            Path empty = Files.createFile(directory.resolve("empty.txt"));
            outsider.guiTep(empty.toFile());
            assertReceivedBytes(memberMessages, new byte[0]);
            assertReceivedBytes(senderMessages, new byte[0]);
            assertTrue(outsiderMessages.poll(1, TimeUnit.SECONDS).outgoing());
            bob.close(); // Tương ứng đăng xuất: ngừng nhận, vẫn phục vụ người khác.
            alice.guiTep(source.toFile());
            assertReceivedBytes(outsiderMessages, bytes);
            assertNull(memberMessages.poll(300, TimeUnit.MILLISECONDS));
            assertThrows(java.io.IOException.class, () -> bob.guiTep(source.toFile()));
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }

    private void assertReceivedBytes(BlockingQueue<ChatMessage> queue, byte[] expected) throws Exception {
        ChatMessage message = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "Client phải nhận tệp broadcast");
        assertTrue(message.file());
        assertFalse(message.outgoing());
        Path saved = Path.of(message.content().split("\nĐã lưu: ", 2)[1]);
        try { assertArrayEquals(expected, Files.readAllBytes(saved)); }
        finally { Files.deleteIfExists(saved); }
    }
}
