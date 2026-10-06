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

    @BeforeEach void start() throws IOException {
        registry = new UserRegistry(directory.resolve("accounts.properties"));
        server = new ChatServer("127.0.0.1", 0, registry, activity::add);
        server.start();
    }

    @AfterEach void stop() {
        clients.forEach(ChatClient::close);
        server.close();
    }

    private ChatClient client() {
        ChatClient client = new ChatClient(directory);
        clients.add(client);
        return client;
    }

    private void connect(ChatClient client, String name) throws Exception {
        if (registry.snapshot().stream().noneMatch(user -> user.username().equals(name))) registry.register(name, "password123");
        client.connect("127.0.0.1", server.getPort(), name, "password123", false).get(10, TimeUnit.SECONDS);
    }

    private static <T> T await(BlockingQueue<T> queue, Predicate<T> predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            T value = queue.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (value != null && predicate.test(value)) return value;
        }
        throw new AssertionError("Timed out waiting for expected event");
    }

    @Test void privateMessagesPreserveUnicodePipesAndConcurrentResponses() throws Exception {
        ChatClient alice = client(), bob = client(), charlie = client();
        BlockingQueue<ChatMessage> inbox = new LinkedBlockingQueue<>();
        BlockingQueue<ChatMessage> otherInbox = new LinkedBlockingQueue<>();
        bob.setOnChat(inbox::add);
        charlie.setOnChat(otherInbox::add);
        connect(alice, "Alice"); connect(bob, "Bób"); connect(charlie, "Charlie");
        String content = "Xin chào | TCP | 😀\nDòng thứ hai";
        alice.sendMessage("Bób", content).get(5, TimeUnit.SECONDS);
        ChatMessage message = await(inbox, item -> item.content().contains("Xin chào"));
        assertEquals(content, message.content());
        assertEquals("Alice", message.sender());
        assertFalse(message.outgoing());
        assertNull(otherInbox.poll(150, TimeUnit.MILLISECONDS));
        List<CompletableFuture<Void>> sends = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sends.add(alice.sendMessage("Bób", "A" + i));
            sends.add(charlie.sendMessage("Bób", "C" + i));
            sends.add(bob.refreshUsers());
        }
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        Set<ChatMessage> received = new HashSet<>();
        for (int i = 0; i < 80; i++) received.add(await(inbox, text -> true));
        assertEquals(80, received.size());
    }

    @Test void duplicateLoginDoesNotRemoveOriginalAndReconnectWorks() throws Exception {
        ChatClient alice = client(), duplicate = client(), observer = client();
        BlockingQueue<List<String>> lists = new LinkedBlockingQueue<>();
        observer.setOnUsers(lists::add);
        connect(alice, "Alice"); connect(observer, "Observer");
        assertThrows(ExecutionException.class, () -> connect(duplicate, "Alice"));
        observer.refreshUsers().get(5, TimeUnit.SECONDS);
        assertTrue(await(lists, names -> names.contains("Alice")).contains("Alice"));
        lists.clear();
        alice.disconnect();
        await(lists, names -> !names.contains("Alice"));
        connect(alice, "Alice");
        await(lists, names -> names.contains("Alice"));
        connect(duplicate, "Bob");
        await(lists, names -> names.containsAll(List.of("Alice", "Bob")));
    }

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
        connect(alice, "Alice"); connect(bob, "Bob");
        byte[] data = new byte[1024 * 1024];
        new Random(42).nextBytes(data);
        Path source = directory.resolve("ảnh.bin");
        Files.write(source, data);
        CompletableFuture.allOf(alice.sendFile("Bob", source.toFile()), bob.sendFile("Alice", source.toFile()),
                alice.sendMessage("Bob", "after file | OK")).get(10, TimeUnit.SECONDS);
        File first = await(bobFiles, file -> true);
        assertArrayEquals(data, Files.readAllBytes(first.toPath()));
        assertArrayEquals(data, Files.readAllBytes(await(aliceFiles, file -> true).toPath()));
        await(bobMessages, text -> text.endsWith("after file | OK"));
        alice.sendFile("Bob", source.toFile()).get(5, TimeUnit.SECONDS);
        File second = await(bobFiles, file -> true);
        assertNotEquals(first, second);
        assertArrayEquals(data, Files.readAllBytes(first.toPath()));
        Path empty = Files.createFile(directory.resolve("empty.txt"));
        alice.sendFile("Bob", empty.toFile()).get(5, TimeUnit.SECONDS);
        assertEquals(0, await(bobFiles, file -> true).length());
    }

    @Test void offlineRecipientAndOversizedTextFailWithoutBreakingTcp() throws Exception {
        ChatClient alice = client(); connect(alice, "Alice");
        assertThrows(ExecutionException.class, () -> alice.sendMessage("Offline", "hello").get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, () -> alice.sendMessage("Alice", "x".repeat(8001)).get(5, TimeUnit.SECONDS));
        alice.refreshUsers().get(5, TimeUnit.SECONDS);
    }

    private static File receivedFile(ChatMessage item) {
        return Path.of(item.content().split("\nĐã lưu: ", 2)[1]).toFile();
    }

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
        connect(client(), "StillWorks");
    }

    @Test void signupPasswordLoginAndServerMetadataStayConsistent() throws Exception {
        ChatClient alice = client();
        BlockingQueue<List<UserInfo>> changes = new LinkedBlockingQueue<>();
        registry.setOnChange(changes::add);
        alice.connect("127.0.0.1", server.getPort(), "Alice", "Secret123!", true).get(10, TimeUnit.SECONDS);
        UserInfo online = await(changes, list -> list.stream().anyMatch(UserInfo::online)).getFirst();
        assertEquals("127.0.0.1", online.ip());
        assertNotNull(online.loginTime());
        changes.clear();
        alice.disconnect();
        UserInfo offline = await(changes, list -> !list.getFirst().online()).getFirst();
        assertNotNull(offline.logoutTime());
        assertThrows(ExecutionException.class, () -> alice.connect("127.0.0.1", server.getPort(), "Alice", "Wrong123!", false).get(10, TimeUnit.SECONDS));
        assertFalse(registry.snapshot().getFirst().online());
        alice.connect("127.0.0.1", server.getPort(), "Alice", "Secret123!", false).get(10, TimeUnit.SECONDS);
        assertTrue(registry.snapshot().getFirst().online());
        server.close();
        assertFalse(registry.snapshot().getFirst().online());
        assertNotNull(registry.snapshot().getFirst().logoutTime());
    }

    @Test void serverShutdownClearsClientStateAndAllowsReconnect() throws Exception {
        ChatClient alice = client();
        BlockingQueue<Boolean> states = new LinkedBlockingQueue<>();
        alice.setOnConnected(states::add);
        connect(alice, "Alice");
        await(states, state -> state);
        server.close();
        await(states, state -> !state);
        assertThrows(ExecutionException.class, () -> alice.refreshUsers().get(5, TimeUnit.SECONDS));
        server = new ChatServer(0, registry, ignored -> {});
        server.start();
        connect(alice, "Alice");
        await(states, state -> state);
        alice.refreshUsers().get(5, TimeUnit.SECONDS);
    }

    @Test void multicastReachesSubscribersAndSupportsLeaveAndRejoin() throws Exception {
        ChatClient aliceTcp = client(), bobTcp = client(), outsiderTcp = client();
        connect(aliceTcp, "Alice"); connect(bobTcp, "Bob"); connect(outsiderTcp, "Outside");
        try (MulticastChatService alice = new MulticastChatService("230.0.0.1", 5006);
             MulticastChatService bob = new MulticastChatService("230.0.0.1", 5006);
             MulticastChatService outsider = new MulticastChatService("230.0.0.1", 5006)) {
            BlockingQueue<ChatMessage> a = new LinkedBlockingQueue<>(), b = new LinkedBlockingQueue<>(), c = new LinkedBlockingQueue<>();
            alice.setOnMessageReceived(a::add); bob.setOnMessageReceived(b::add); outsider.setOnMessageReceived(c::add);
            assertFalse(alice.isJoined()); assertFalse(bob.isJoined()); assertFalse(outsider.isJoined());
            alice.joinGroup("Alice", aliceTcp.getLocalAddress()); bob.joinGroup("Bob", bobTcp.getLocalAddress());
            alice.sendMessage("nhóm | xin chào");
            assertEquals("Alice", await(b, item -> item.content().equals("nhóm | xin chào")).sender());
            assertNull(c.poll(150, TimeUnit.MILLISECONDS));
            bob.leaveGroup();
            alice.sendMessage("after leave");
            assertNull(b.poll(200, TimeUnit.MILLISECONDS));
            bob.joinGroup("Bob", bobTcp.getLocalAddress());
            bob.sendMessage("back");
            assertEquals("Bob", await(a, item -> item.content().equals("back")).sender());
            assertThrows(IOException.class, () -> bob.sendMessage("x".repeat(4096)));
            bobTcp.refreshUsers().get(5, TimeUnit.SECONDS);
        }
    }

    @Test void bindsSelectedIpAndRejectsInvalidOrUnavailableAddresses() throws Exception {
        assertEquals("127.0.0.1", server.getAddress());
        for (String ip : List.of("", "localhost", "127.1", "256.1.1.1", "1.2.3.4.5", "239.255.42.99")) {
            assertThrows(IOException.class, () -> new ChatServer(ip, 0, registry, activity::add), ip);
        }
        assertThrows(IOException.class, () -> new ChatServer("127.0.0.1", server.getPort(), registry, activity::add));
        try (ChatServer wildcard = new ChatServer("0.0.0.0", 0, registry, activity::add)) {
            assertEquals("0.0.0.0", wildcard.getAddress());
        }
        connect(client(), "StillWorks");
    }

    @Test void duplicateSignupCanBeCorrectedWithoutChangingExistingPassword() throws Exception {
        ChatClient original = client(), applicant = client();
        original.connect("127.0.0.1", server.getPort(), "Alice", "Original123!", true).get(10, TimeUnit.SECONDS);
        BlockingQueue<Boolean> states = new LinkedBlockingQueue<>();
        applicant.setOnConnected(states::add);
        ExecutionException duplicate = assertThrows(ExecutionException.class,
                () -> applicant.connect("127.0.0.1", server.getPort(), "Alice", "Different123!", true).get(10, TimeUnit.SECONDS));
        assertTrue(duplicate.getCause().getMessage().contains("đã tồn tại"));
        assertNull(states.poll(), "Failed signup must not emit a logout event");
        assertEquals(1, registry.snapshot().size());
        assertEquals("Original123!", registry.snapshot().getFirst().password());
        assertThrows(IOException.class, () -> registry.authenticate("Alice", "Different123!", false));
        applicant.connect("127.0.0.1", server.getPort(), "Bob", "Different123!", true).get(10, TimeUnit.SECONDS);
        assertTrue(await(states, state -> state));
        assertEquals(2, registry.snapshot().size());
        assertTrue(registry.snapshot().stream().allMatch(UserInfo::online));
    }

    @Test void signupCanRetryAfterServerWasUnavailable() throws Exception {
        int port = server.getPort();
        server.close();
        ChatClient applicant = client();
        BlockingQueue<Boolean> states = new LinkedBlockingQueue<>();
        applicant.setOnConnected(states::add);
        assertThrows(ExecutionException.class,
                () -> applicant.connect("127.0.0.1", port, "Alice", "Secret123!", true).get(10, TimeUnit.SECONDS));
        assertTrue(registry.snapshot().isEmpty());
        assertNull(states.poll());
        server = new ChatServer("127.0.0.1", port, registry, activity::add);
        server.start();
        applicant.connect("127.0.0.1", port, "Alice", "Secret123!", true).get(10, TimeUnit.SECONDS);
        assertTrue(await(states, state -> state));
        assertEquals("Alice", registry.snapshot().getFirst().username());
    }

    @Test void activityCoversSignupLoginMessagesFilesFailuresAndDisconnectWithoutPasswords() throws Exception {
        ChatClient alice = client(), bob = client(), invalid = client();
        alice.connect("127.0.0.1", server.getPort(), "Alice", "Secret123!", true).get(10, TimeUnit.SECONDS);
        await(activity, text -> text.equals("Alice đã đăng ký tài khoản"));
        await(activity, text -> text.equals("Alice đã đăng nhập từ 127.0.0.1"));
        assertEquals("Secret123!", registry.snapshot().getFirst().password());
        connect(bob, "Bob");
        alice.sendMessage("Bob", "Xin chào").get(5, TimeUnit.SECONDS);
        await(activity, text -> text.equals("Alice đã gửi tin nhắn cho Bob"));
        Path file = Files.writeString(directory.resolve("sample.txt"), "hello");
        alice.sendFile("Bob", file.toFile()).get(5, TimeUnit.SECONDS);
        await(activity, text -> text.equals("Alice đã gửi tệp sample.txt (5 byte) cho Bob"));
        assertThrows(ExecutionException.class,
                () -> alice.sendMessage("Offline", "hello").get(5, TimeUnit.SECONDS));
        await(activity, text -> text.contains("Alice (127.0.0.1) · MESSAGE thất bại"));
        assertThrows(ExecutionException.class,
                () -> invalid.connect("127.0.0.1", server.getPort(), "Alice", "Wrong123!", false).get(10, TimeUnit.SECONDS));
        String failure = await(activity, text -> text.contains("Alice (127.0.0.1) · LOGIN thất bại"));
        assertFalse(failure.contains("Wrong123!"));
        assertFalse(failure.contains("Secret123!"));
        alice.disconnect();
        await(activity, text -> text.equals("Alice đã ngắt kết nối"));
    }
}
