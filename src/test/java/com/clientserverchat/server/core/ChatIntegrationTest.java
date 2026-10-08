/*
 * File: ChatIntegrationTest.java
 * Vai trò: Test tích hợp client-server TCP và multicast.
 * Mục đích: Chạy server thật trên localhost, nhiều client thật, kiểm tra tin nhắn, file, đăng nhập, lỗi và metadata.
 */
package com.clientserverchat.server.core;

import com.clientserverchat.client.core.ChatClient;
import com.clientserverchat.common.Protocol;
import com.clientserverchat.client.core.MulticastChatService;
import com.clientserverchat.client.core.ChatMessage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ChatIntegrationTest {
    @TempDir Path directory;
    private ChatServer server;
    private UserRegistry registry;
    private final List<ChatClient> clients = new ArrayList<>();
    private final BlockingQueue<String> activity = new LinkedBlockingQueue<>();

    /** Tạo UserRegistry tạm và khởi động ChatServer trước mỗi test. */
    @BeforeEach void start() throws IOException {
        registry = new UserRegistry(directory.resolve("accounts.txt"));
        server = new ChatServer("127.0.0.1", 0, registry, activity::add);
        server.start();
    }

    /** Đóng toàn bộ client và server sau mỗi test. */
    @AfterEach void stop() {
        clients.forEach(ChatClient::close);
        server.close();
    }

    /** Tạo ChatClient dùng thư mục temp để file nhận trong test không lẫn dữ liệu thật. */
    private ChatClient client() {
        ChatClient client = new ChatClient(directory);
        clients.add(client);
        return client;
    }

    /** Helper đăng ký tài khoản nếu chưa có rồi đăng nhập client vào server test. */
    private void dangNhapClientTest(ChatClient client, String name) throws Exception {
        if (registry.danhSachNguoiDung().stream().noneMatch(user -> user.username().equals(name))) registry.dangKi(name, "password123");
        client.dangNhap("127.0.0.1", server.getPort(), name, "password123").get(10, TimeUnit.SECONDS);
    }

    /** Chờ một item trong queue thỏa điều kiện, dùng cho sự kiện mạng bất đồng bộ. */
    private static <T> T await(BlockingQueue<T> queue, Predicate<T> predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            T value = queue.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (value != null && predicate.test(value)) return value;
        }
        throw new AssertionError("Timed out waiting for expected event");
    }

    /** Kiểm tra tin nhắn riêng giữ Unicode/ký tự | và nhiều request đồng thời không lẫn phản hồi. */
    @Test void privateMessagesPreserveUnicodePipesAndConcurrentResponses() throws Exception {
        ChatClient alice = client(), bob = client(), charlie = client();
        BlockingQueue<ChatMessage> inbox = new LinkedBlockingQueue<>();
        BlockingQueue<ChatMessage> otherInbox = new LinkedBlockingQueue<>();
        bob.setOnChat(inbox::add);
        charlie.setOnChat(otherInbox::add);
        dangNhapClientTest(alice, "Alice"); dangNhapClientTest(bob, "Bób"); dangNhapClientTest(charlie, "Charlie");
        String content = "Xin chào | TCP | 😀\nDòng thứ hai";
        alice.guiTinNhan("Bób", content).get(5, TimeUnit.SECONDS);
        ChatMessage message = await(inbox, item -> item.content().contains("Xin chào"));
        assertEquals(content, message.content());
        assertEquals("Alice", message.sender());
        assertFalse(message.outgoing());
        assertNull(otherInbox.poll(150, TimeUnit.MILLISECONDS));
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sends.add(alice.guiTinNhan("Bób", "A" + i));
            sends.add(charlie.guiTinNhan("Bób", "C" + i));
            sends.add(bob.lamMoiDanhSachNguoiDung());
        }
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        Set<ChatMessage> received = new HashSet<>();
        for (int i = 0; i < 80; i++) received.add(await(inbox, text -> true));
        assertEquals(80, received.size());
    }

    /** Kiểm tra đăng nhập trùng bị chặn nhưng phiên gốc vẫn sống và đăng nhập lại được. */
    @Test void duplicateLoginDoesNotRemoveOriginalAndReconnectWorks() throws Exception {
        ChatClient alice = client(), duplicate = client(), observer = client();
        BlockingQueue<List<String>> lists = new LinkedBlockingQueue<>();
        observer.setOnUsers(lists::add);
        dangNhapClientTest(alice, "Alice"); dangNhapClientTest(observer, "Observer");
        assertThrows(ExecutionException.class, () -> dangNhapClientTest(duplicate, "Alice"));
        observer.lamMoiDanhSachNguoiDung().get(5, TimeUnit.SECONDS);
        assertTrue(await(lists, names -> names.contains("Alice")).contains("Alice"));
        lists.clear();
        alice.dangXuat();
        await(lists, names -> !names.contains("Alice"));
        dangNhapClientTest(alice, "Alice");
        await(lists, names -> names.contains("Alice"));
        dangNhapClientTest(duplicate, "Bob");
        await(lists, names -> names.containsAll(List.of("Alice", "Bob")));
    }

    /** Kiểm tra gửi file hai chiều đồng thời không làm hỏng file và không làm lẫn tin nhắn sau đó. */
    @Test void simultaneousFilesRemainIntactAndDoNotCorruptFollowingMessages() throws Exception {
        ChatClient alice = client(), bob = client();
        BlockingQueue<File> aliceFiles = new LinkedBlockingQueue<>(), bobFiles = new LinkedBlockingQueue<>();
        BlockingQueue<String> bobMessages = new LinkedBlockingQueue<>();
        alice.setOnChat(item -> {
            if (item.file() && !item.outgoing()) aliceFiles.add(receivedFile(item));
        });
        bob.setOnChat(item -> {
            if (item.outgoing()) return;
            if (item.file()) bobFiles.add(receivedFile(item));
            else bobMessages.add(item.content());
        });
        dangNhapClientTest(alice, "Alice"); dangNhapClientTest(bob, "Bob");
        byte[] data = new byte[1024 * 1024];
        new Random(42).nextBytes(data);
        Path source = directory.resolve("ảnh.bin");
        Files.write(source, data);
        CompletableFuture.allOf(alice.guiTep("Bob", source.toFile()), bob.guiTep("Alice", source.toFile()),
                alice.guiTinNhan("Bob", "after file | OK")).get(10, TimeUnit.SECONDS);
        File first = await(bobFiles, file -> true);
        assertArrayEquals(data, Files.readAllBytes(first.toPath()));
        assertArrayEquals(data, Files.readAllBytes(await(aliceFiles, file -> true).toPath()));
        await(bobMessages, text -> text.endsWith("after file | OK"));
        alice.guiTep("Bob", source.toFile()).get(5, TimeUnit.SECONDS);
        File second = await(bobFiles, file -> true);
        assertNotEquals(first, second);
        assertArrayEquals(data, Files.readAllBytes(first.toPath()));
        Path empty = Files.createFile(directory.resolve("empty.txt"));
        alice.guiTep("Bob", empty.toFile()).get(5, TimeUnit.SECONDS);
        assertEquals(0, await(bobFiles, file -> true).length());
    }

    /** Kiểm tra gửi tới người offline hoặc tin quá dài đều lỗi nhưng TCP vẫn dùng tiếp được. */
    @Test void offlineRecipientAndOversizedTextFailWithoutBreakingTcp() throws Exception {
        ChatClient alice = client(); dangNhapClientTest(alice, "Alice");
        assertThrows(ExecutionException.class, () -> alice.guiTinNhan("Offline", "hello").get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> alice.guiTinNhan("Alice", "x".repeat(8001)).get(5, TimeUnit.SECONDS));
        alice.lamMoiDanhSachNguoiDung().get(5, TimeUnit.SECONDS);
    }

    /** Lấy đường dẫn file đã lưu từ nội dung ChatMessage file. */
    private static File receivedFile(ChatMessage item) {
        return Path.of(item.content().split("\nĐã lưu: ", 2)[1]).toFile();
    }

    /** Kiểm tra request sai giao thức hoặc chưa đăng nhập không làm server sập. */
    @Test void malformedFramesAndUnauthenticatedRequestsDoNotBreakServer() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(2000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            Protocol.write(out, new Protocol.Packet("MESSAGE", 1, "Alice", "spoofed"));
            assertEquals("ERROR", Protocol.read(in).type());
            out.writeInt(Protocol.MAX_FRAME_BYTES + 1); out.flush();
            assertEquals(-1, in.read());
        }
        dangNhapClientTest(client(), "StillWorks");
    }

    /** Kiểm tra đăng ký, đăng nhập, mật khẩu rõ, IP, giờ vào/ra và metadata server nhất quán. */
    @Test void signupPasswordLoginAndServerMetadataStayConsistent() throws Exception {
        ChatClient alice = client();
        BlockingQueue<List<UserInfo>> changes = new LinkedBlockingQueue<>();
        registry.khiDuLieuThayDoi(changes::add);
        alice.dangKi("127.0.0.1", server.getPort(), "Alice", "Secret123!").get(10, TimeUnit.SECONDS);
        UserInfo online = await(changes, list -> list.stream().anyMatch(UserInfo::online)).getFirst();
        assertEquals("127.0.0.1", online.ip());
        assertNotNull(online.loginTime());
        changes.clear();
        alice.dangXuat();
        UserInfo offline = await(changes, list -> !list.getFirst().online()).getFirst();
        assertNotNull(offline.logoutTime());
        assertThrows(ExecutionException.class, () -> alice.dangNhap("127.0.0.1", server.getPort(), "Alice", "Wrong123!").get(10, TimeUnit.SECONDS));
        assertFalse(registry.danhSachNguoiDung().getFirst().online());
        alice.dangNhap("127.0.0.1", server.getPort(), "Alice", "Secret123!").get(10, TimeUnit.SECONDS);
        assertTrue(registry.danhSachNguoiDung().getFirst().online());
        server.close();
        assertFalse(registry.danhSachNguoiDung().getFirst().online());
        assertNotNull(registry.danhSachNguoiDung().getFirst().logoutTime());
    }

    /** Kiểm tra server tắt sẽ làm client mất trạng thái và sau đó có thể reconnect. */
    @Test void serverShutdownClearsClientStateAndAllowsReconnect() throws Exception {
        ChatClient alice = client();
        BlockingQueue<Boolean> states = new LinkedBlockingQueue<>();
        alice.setOnConnected(states::add);
        dangNhapClientTest(alice, "Alice");
        await(states, state -> state);
        server.close();
        await(states, state -> !state);
        assertThrows(ExecutionException.class, () -> alice.lamMoiDanhSachNguoiDung().get(5, TimeUnit.SECONDS));
        server = new ChatServer(0, registry, ignored -> {});
        server.start();
        dangNhapClientTest(alice, "Alice");
        await(states, state -> state);
        alice.lamMoiDanhSachNguoiDung().get(5, TimeUnit.SECONDS);
    }

    /** Kiểm tra multicast gửi tới client đã join, không tới outsider, hỗ trợ rời và join lại. */
    @Test void multicastReachesSubscribersAndSupportsLeaveAndRejoin() throws Exception {
        ChatClient aliceTcp = client(), bobTcp = client(), outsiderTcp = client();
        dangNhapClientTest(aliceTcp, "Alice"); dangNhapClientTest(bobTcp, "Bob"); dangNhapClientTest(outsiderTcp, "Outside");
        try (MulticastChatService alice = new MulticastChatService("230.0.0.1", 5006);
             MulticastChatService bob = new MulticastChatService("230.0.0.1", 5006);
             MulticastChatService outsider = new MulticastChatService("230.0.0.1", 5006)) {
            BlockingQueue<ChatMessage> a = new LinkedBlockingQueue<>(), b = new LinkedBlockingQueue<>(), c = new LinkedBlockingQueue<>();
            alice.setOnMessageReceived(a::add); bob.setOnMessageReceived(b::add); outsider.setOnMessageReceived(c::add);
            assertFalse(alice.daThamGia()); assertFalse(bob.daThamGia()); assertFalse(outsider.daThamGia());
            alice.thamGiaPhongMulticast("Alice", aliceTcp.layDiaChiMayClient()); bob.thamGiaPhongMulticast("Bob", bobTcp.layDiaChiMayClient());
            alice.guiTinNhanNhom("nhóm | xin chào");
            assertEquals("Alice", await(b, item -> item.content().equals("nhóm | xin chào")).sender());
            assertNull(c.poll(150, TimeUnit.MILLISECONDS));
            bob.roiPhongMulticast();
            alice.guiTinNhanNhom("after leave");
            assertNull(b.poll(200, TimeUnit.MILLISECONDS));
            bob.thamGiaPhongMulticast("Bob", bobTcp.layDiaChiMayClient());
            bob.guiTinNhanNhom("back");
            assertEquals("Bob", await(a, item -> item.content().equals("back")).sender());
            assertThrows(IOException.class, () -> bob.guiTinNhanNhom("x".repeat(4096)));
            bobTcp.lamMoiDanhSachNguoiDung().get(5, TimeUnit.SECONDS);
        }
    }

    /** Kiểm tra server chỉ bind IP hợp lệ và từ chối IP/cổng không dùng được. */
    @Test void bindsSelectedIpAndRejectsInvalidOrUnavailableAddresses() throws Exception {
        assertEquals("127.0.0.1", server.getAddress());
        for (String ip : List.of("", "localhost", "127.1", "256.1.1.1", "1.2.3.4.5", "239.255.42.99")) {
            assertThrows(IOException.class, () -> new ChatServer(ip, 0, registry, activity::add), ip);
        }
        assertThrows(IOException.class, () -> new ChatServer("127.0.0.1", server.getPort(), registry, activity::add));
        try (ChatServer wildcard = new ChatServer("0.0.0.0", 0, registry, activity::add)) {
            assertEquals("0.0.0.0", wildcard.getAddress());
        }
        dangNhapClientTest(client(), "StillWorks");
    }

    /** Kiểm tra đăng ký trùng không đổi mật khẩu cũ và người dùng sửa tên để đăng ký lại được. */
    @Test void duplicateSignupCanBeCorrectedWithoutChangingExistingPassword() throws Exception {
        ChatClient original = client(), applicant = client();
        original.dangKi("127.0.0.1", server.getPort(), "Alice", "Original123!").get(10, TimeUnit.SECONDS);
        BlockingQueue<Boolean> states = new LinkedBlockingQueue<>();
        applicant.setOnConnected(states::add);
        ExecutionException duplicate = assertThrows(ExecutionException.class,
                () -> applicant.dangKi("127.0.0.1", server.getPort(), "Alice", "Different123!").get(10, TimeUnit.SECONDS));
        assertTrue(duplicate.getCause().getMessage().contains("đã tồn tại"));
        assertNull(states.poll(), "Failed signup must not emit a logout event");
        assertEquals(1, registry.danhSachNguoiDung().size());
        assertEquals("Original123!", registry.danhSachNguoiDung().getFirst().password());
        assertThrows(IOException.class, () -> registry.xacThuc("Alice", "Different123!", false));
        applicant.dangKi("127.0.0.1", server.getPort(), "Bob", "Different123!").get(10, TimeUnit.SECONDS);
        assertTrue(await(states, state -> state));
        assertEquals(2, registry.danhSachNguoiDung().size());
        assertTrue(registry.danhSachNguoiDung().stream().allMatch(UserInfo::online));
    }

    /** Kiểm tra đăng ký thất bại do server tắt có thể thử lại khi server mở lại. */
    @Test void signupCanRetryAfterServerWasUnavailable() throws Exception {
        int port = server.getPort();
        server.close();
        ChatClient applicant = client();
        BlockingQueue<Boolean> states = new LinkedBlockingQueue<>();
        applicant.setOnConnected(states::add);
        assertThrows(ExecutionException.class,
                () -> applicant.dangKi("127.0.0.1", port, "Alice", "Secret123!").get(10, TimeUnit.SECONDS));
        assertTrue(registry.danhSachNguoiDung().isEmpty());
        assertNull(states.poll());
        server = new ChatServer("127.0.0.1", port, registry, activity::add);
        server.start();
        applicant.dangKi("127.0.0.1", port, "Alice", "Secret123!").get(10, TimeUnit.SECONDS);
        assertTrue(await(states, state -> state));
        assertEquals("Alice", registry.danhSachNguoiDung().getFirst().username());
    }

    /** Kiểm tra nhật ký server có đăng ký, đăng nhập, gửi tin/file, lỗi và ngắt kết nối. */
    @Test void activityCoversSignupLoginMessagesFilesFailuresAndDisconnectWithoutPasswords() throws Exception {
        ChatClient alice = client(), bob = client(), invalid = client();
        alice.dangKi("127.0.0.1", server.getPort(), "Alice", "Secret123!").get(10, TimeUnit.SECONDS);
        await(activity, text -> text.equals("Alice đã đăng ký tài khoản"));
        await(activity, text -> text.equals("Alice đã đăng nhập từ 127.0.0.1"));
        assertEquals("Secret123!", registry.danhSachNguoiDung().getFirst().password());
        dangNhapClientTest(bob, "Bob");
        alice.guiTinNhan("Bob", "Xin chào").get(5, TimeUnit.SECONDS);
        await(activity, text -> text.equals("Alice đã gửi tin nhắn cho Bob"));
        Path file = Files.writeString(directory.resolve("sample.txt"), "hello");
        alice.guiTep("Bob", file.toFile()).get(5, TimeUnit.SECONDS);
        await(activity, text -> text.equals("Alice đã gửi tệp sample.txt (5 byte) cho Bob"));
        assertThrows(ExecutionException.class,
                () -> alice.guiTinNhan("Offline", "hello").get(5, TimeUnit.SECONDS));
        await(activity, text -> text.contains("Alice (127.0.0.1) · MESSAGE thất bại"));
        assertThrows(ExecutionException.class,
                () -> invalid.dangNhap("127.0.0.1", server.getPort(), "Alice", "Wrong123!").get(10, TimeUnit.SECONDS));
        String failure = await(activity, text -> text.contains("Alice (127.0.0.1) · LOGIN thất bại"));
        assertFalse(failure.contains("Wrong123!"));
        assertFalse(failure.contains("Secret123!"));
        alice.dangXuat();
        await(activity, text -> text.equals("Alice đã ngắt kết nối"));
    }
}
