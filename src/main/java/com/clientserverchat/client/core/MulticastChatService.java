package com.clientserverchat.client.core;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Dịch vụ mạng UDP Multicast cho chức năng Chat nhóm độc lập.
 * 
 * Quản lý vòng đời của MulticastSocket:
 * - Tham gia nhóm multicast (joinGroup)
 * - Lắng nghe tin nhắn ngầm qua DatagramPacket trên luồng nền (receive)
 * - Gửi tin nhắn trực tiếp tới nhóm bằng UDP DatagramPacket (send)
 * - Rời nhóm và giải phóng socket an toàn (leaveGroup)
 * - Tự động phát hiện NetworkInterface phù hợp trên Windows (Wi-Fi, Ethernet, VirtualBox, VMware)
 */
public final class MulticastChatService implements AutoCloseable {
    public static final String DEFAULT_GROUP_IP = "230.0.0.1";
    public static final int DEFAULT_GROUP_PORT = 5000;
    public static final int MAX_PACKET_BYTES = 4096;

    private final String groupIp;
    private final int groupPort;

    private volatile MulticastSocket socket;
    private volatile InetSocketAddress groupSocketAddress;
    private volatile NetworkInterface networkInterface;
    private volatile Thread receiverThread;
    private volatile String currentUsername;
    private volatile boolean joined;
    private volatile GroupFileTransfer files;

    // Cache ngăn hiển thị trùng tin nhắn do chính mình gửi khi nhận lại qua multicast loopback
    private final Set<String> sentMessageSignatures = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Cache khử trùng các gói tin lặp trên tầng mạng
    private final Set<String> receivedMessageSignatures = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Callbacks thông báo về Controller / UI
    private Consumer<ChatMessage> onMessageReceived = msg -> {};
    private Consumer<String> onSystemNotice = notice -> {};
    private Consumer<String> onError = err -> {};
    private Consumer<Boolean> onGroupStateChanged = state -> {};

    public MulticastChatService() {
        this(
            System.getProperty("multicast.group", DEFAULT_GROUP_IP).trim(),
            Integer.parseInt(System.getProperty("multicast.port", String.valueOf(DEFAULT_GROUP_PORT)).trim())
        );
    }

    public MulticastChatService(String groupIp, int groupPort) {
        this.groupIp = groupIp;
        this.groupPort = groupPort;
    }

    public void setOnMessageReceived(Consumer<ChatMessage> callback) {
        this.onMessageReceived = Objects.requireNonNull(callback);
    }

    public void setOnSystemNotice(Consumer<String> callback) {
        this.onSystemNotice = Objects.requireNonNull(callback);
    }

    public void setOnError(Consumer<String> callback) {
        this.onError = Objects.requireNonNull(callback);
    }

    public void setOnGroupStateChanged(Consumer<Boolean> callback) {
        this.onGroupStateChanged = Objects.requireNonNull(callback);
    }

    public boolean isJoined() {
        return joined;
    }

    /**
     * Tham gia vào nhóm Multicast.
     * Khởi tạo MulticastSocket với SO_REUSEADDR để nhiều client cùng máy có thể bind cùng port 5000.
     */
    public synchronized void joinGroup(String username, InetAddress localTcpAddress) throws IOException {
        if (joined) return;
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Tên người dùng không hợp lệ");
        }
        this.currentUsername = username.trim();

        InetAddress mcastAddr;
        try {
            mcastAddr = InetAddress.getByName(groupIp);
            if (!mcastAddr.isMulticastAddress()) {
                throw new IOException("Địa chỉ không phải IPv4 Multicast hợp lệ: " + groupIp);
            }
        } catch (UnknownHostException e) {
            throw new IOException("Không thể phân giải địa chỉ multicast: " + groupIp, e);
        }

        this.groupSocketAddress = new InetSocketAddress(mcastAddr, groupPort);
        this.networkInterface = selectNetworkInterface(localTcpAddress);

        try {
            // Khởi tạo socket chưa bind để thiết lập SO_REUSEADDR trước khi bind port
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(groupPort));

            if (networkInterface != null) {
                try {
                    socket.setNetworkInterface(networkInterface);
                } catch (SocketException ignored) {
                    // Một số card mạng có thể không hỗ trợ setNetworkInterface trực tiếp
                }
            }

            // TTL = 32 để gói tin UDP multicast có thể đi qua router trong mạng LAN hoặc máy ảo (VMware, VirtualBox)
            socket.setTimeToLive(32);

            try {
                // Bật multicast loopback để các tiến trình khác nhau trên cùng máy vẫn nhận được
                socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
            } catch (Exception ignored) {}

            // Thực hiện joinGroup với NetworkInterface
            if (networkInterface != null) {
                socket.joinGroup(groupSocketAddress, networkInterface);
            } else {
                socket.joinGroup(groupSocketAddress, null);
            }

            files = new GroupFileTransfer(currentUsername, item -> onMessageReceived.accept(item), error -> onError.accept(error));
            joined = true;
            onGroupStateChanged.accept(true);

            // Bắt đầu background thread nhận tin nhắn liên tục bằng receive(), không block UI thread
            MulticastSocket receivingSocket = socket;
            receiverThread = Thread.ofPlatform()
                    .daemon(true)
                    .name("udp-multicast-receiver-" + currentUsername)
                    .start(() -> receiveLoop(receivingSocket));

            // Gửi sự kiện JOIN|username tới toàn bộ thành viên trong nhóm
            sendJoin(currentUsername);

        } catch (BindException e) {
            closeSocketQuietly();
            throw new IOException("Cổng " + groupPort + " đang bị xung đột hoặc không thể bind: " + e.getMessage(), e);
        } catch (IOException e) {
            closeSocketQuietly();
            throw new IOException("Không thể tham gia nhóm multicast " + groupIp + ":" + groupPort + ": " + e.getMessage(), e);
        }
    }

    public synchronized void joinGroup(String username) throws IOException {
        joinGroup(username, null);
    }

    /**
     * Gửi tin nhắn văn bản vào nhóm multicast.
     * Format gói tin: MESSAGE|username|timestamp|noi_dung
     * Gửi đúng 1 DatagramPacket trực tiếp đến địa chỉ nhóm (group IP:port).
     */
    public void sendMessage(String message) throws IOException {
        if (!joined || socket == null || socket.isClosed()) {
            throw new IOException("Chưa tham gia phòng chat nhóm Multicast");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IOException("Nội dung tin nhắn không được để trống");
        }

        long timestamp = System.currentTimeMillis();
        String cleanMessage = message.trim();
        String payload = "MESSAGE|" + currentUsername + "|" + timestamp + "|" + cleanMessage;
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > MAX_PACKET_BYTES) {
            throw new IOException("Tin nhắn quá dài (tối đa " + MAX_PACKET_BYTES + " bytes)");
        }

        // Lưu chữ ký để không hiển thị trùng lặp khi gói tin loopback dội về chính client này
        String signature = currentUsername + "|" + timestamp + "|" + cleanMessage;
        sentMessageSignatures.add(signature);

        DatagramPacket packet = new DatagramPacket(
                bytes,
                bytes.length,
                groupSocketAddress.getAddress(),
                groupPort
        );

        try {
            socket.send(packet);
        } catch (IOException e) {
            sentMessageSignatures.remove(signature);
            throw new IOException("Lỗi khi gửi gói tin UDP Multicast: " + e.getMessage(), e);
        }
    }

    public synchronized void sendFile(java.io.File file) throws IOException {
        if (!joined || files == null || socket == null) throw new IOException("Tham gia phòng trước khi gửi tệp.");
        byte[] payload = files.offer(file).getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(payload, payload.length, groupSocketAddress));
        onMessageReceived.accept(new ChatMessage(ChatMessage.GROUP, currentUsername, file.getName(), Instant.now(), true, true));
    }

    /**
     * Gửi sự kiện JOIN|username vào nhóm.
     */
    private void sendJoin(String username) {
        try {
            if (socket != null && !socket.isClosed() && groupSocketAddress != null) {
                byte[] bytes = ("JOIN|" + username).getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(
                        bytes,
                        bytes.length,
                        groupSocketAddress.getAddress(),
                        groupPort
                );
                socket.send(packet);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Rời nhóm Multicast:
     * - Gửi sự kiện LEAVE|username
     * - Gọi socket.leaveGroup()
     * - Đóng MulticastSocket an toàn
     * - Dừng background thread nhận dữ liệu
     */
    public synchronized void leaveGroup() {
        if (!joined) return;
        joined = false;
        if (files != null) { files.close(); files = null; }
        onGroupStateChanged.accept(false);

        // 1. Gửi thông báo LEAVE|username tới nhóm
        try {
            if (socket != null && !socket.isClosed() && currentUsername != null && groupSocketAddress != null) {
                byte[] bytes = ("LEAVE|" + currentUsername).getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(
                        bytes,
                        bytes.length,
                        groupSocketAddress.getAddress(),
                        groupPort
                );
                socket.send(packet);
            }
        } catch (Exception ignored) {}

        // 2. Rời nhóm multicast
        try {
            if (socket != null && !socket.isClosed() && groupSocketAddress != null) {
                if (networkInterface != null) {
                    socket.leaveGroup(groupSocketAddress, networkInterface);
                } else {
                    socket.leaveGroup(groupSocketAddress, null);
                }
            }
        } catch (Exception ignored) {}

        // 3. Đóng socket (điều này sẽ unblock lệnh receive() trong receiverThread)
        closeSocketQuietly();

        // 4. Ngắt thread nhận dữ liệu nếu còn chạy
        if (receiverThread != null && receiverThread.isAlive()) {
            receiverThread.interrupt();
            receiverThread = null;
        }

        sentMessageSignatures.clear();
        receivedMessageSignatures.clear();
    }

    private void closeSocketQuietly() {
        if (files != null) { files.close(); files = null; }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {}
            socket = null;
        }
    }

    /**
     * Vòng lặp lắng nghe DatagramPacket từ nhóm Multicast trên thread riêng biệt.
     */
    private void receiveLoop(MulticastSocket receivingSocket) {
        byte[] buffer = new byte[MAX_PACKET_BYTES];
        while (joined && socket == receivingSocket && !receivingSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receivingSocket.receive(packet);
                if (!joined || socket != receivingSocket) break;

                String payload = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8
                );
                if (payload.startsWith("FILE_OFFER|")) {
                    GroupFileTransfer transfer = files;
                    if (transfer != null) transfer.receive(payload, packet.getAddress());
                } else handleIncomingPayload(payload);

            } catch (SocketException e) {
                // Socket đã được đóng an toàn khi rời nhóm hoặc thoát ứng dụng
                break;
            } catch (IOException e) {
                if (joined && socket != null && !socket.isClosed()) {
                    onError.accept("Lỗi nhận dữ liệu multicast: " + e.getMessage());
                }
                break;
            } catch (Exception e) {
                if (joined) {
                    onError.accept("Lỗi xử lý gói tin multicast: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Xử lý nội dung gói tin nhận được từ Multicast:
     * - JOIN|username -> thông báo "username đã tham gia phòng."
     * - LEAVE|username -> thông báo "username đã rời phòng."
     * - MESSAGE|username|timestamp|noi_dung -> hiển thị tin nhắn và khử trùng lặp
     */
    private void handleIncomingPayload(String payload) {
        if (payload == null || payload.isBlank()) return;
        String[] parts = payload.split("\\|", 4);
        String type = parts[0];

        switch (type) {
            case "JOIN" -> {
                if (parts.length >= 2) {
                    String user = parts[1].trim();
                    if (!user.equals(currentUsername)) {
                        onSystemNotice.accept(user + " đã tham gia phòng.");
                    }
                }
            }
            case "LEAVE" -> {
                if (parts.length >= 2) {
                    String user = parts[1].trim();
                    if (!user.equals(currentUsername)) {
                        onSystemNotice.accept(user + " đã rời phòng.");
                    }
                }
            }
            case "MESSAGE" -> {
                if (parts.length >= 4) {
                    String sender = parts[1].trim();
                    String timestampStr = parts[2].trim();
                    String content = parts[3];

                    String signature = sender + "|" + timestampStr + "|" + content;
                    boolean isMe = sender.equals(currentUsername);

                    // Nếu chính client gửi nhận lại gói tin của mình qua multicast loopback
                    if (isMe) {
                        if (sentMessageSignatures.remove(signature)) {
                            // Gói tin này đã được hiển thị trên UI khi người dùng bấm Gửi -> Bỏ qua, không hiển thị trùng!
                            return;
                        }
                    }

                    // Khử trùng lặp trên mạng
                    if (!receivedMessageSignatures.add(signature)) {
                        return;
                    }
                    if (receivedMessageSignatures.size() > 1000) {
                        receivedMessageSignatures.clear();
                    }

                    Instant time = Instant.now();
                    try {
                        time = Instant.ofEpochMilli(Long.parseLong(timestampStr));
                    } catch (Exception ignored) {}

                    ChatMessage chatMessage = new ChatMessage(
                            ChatMessage.GROUP,
                            sender,
                            content,
                            time,
                            isMe,
                            false
                    );
                    onMessageReceived.accept(chatMessage);
                }
            }
            default -> {
                // Các gói tin khác hoặc không đúng định dạng bị bỏ qua
            }
        }
    }

    /**
     * Lựa chọn NetworkInterface tối ưu trên Windows hỗ trợ nhiều adapter:
     * Wi-Fi, Ethernet, VirtualBox, VMware, máy ảo và LAN.
     */
    private static NetworkInterface selectNetworkInterface(InetAddress preferredAddress) {
        // 1. Kiểm tra cấu hình tường minh qua JVM property
        String configured = System.getProperty("multicast.interface", System.getProperty("chat.interface", "")).trim();
        if (!configured.isEmpty()) {
            try {
                NetworkInterface nif = NetworkInterface.getByName(configured);
                if (nif != null && isUsable(nif)) return nif;
            } catch (Exception ignored) {}
        }

        // 2. Nếu có địa chỉ local từ kết nối TCP client đến server
        if (preferredAddress != null && !preferredAddress.isLoopbackAddress()) {
            try {
                NetworkInterface nif = NetworkInterface.getByInetAddress(preferredAddress);
                if (nif != null && isUsable(nif)) return nif;
            } catch (Exception ignored) {}
        }

        // 3. Truy vấn OS routing bằng dummy UDP socket (không gửi packet ra ngoài)
        try (DatagramSocket route = new DatagramSocket()) {
            route.connect(InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1}), 9);
            NetworkInterface routed = NetworkInterface.getByInetAddress(route.getLocalAddress());
            if (routed != null && isUsable(routed) && !routed.isLoopback()) {
                return routed;
            }
        } catch (Exception ignored) {}

        // 4. Quét danh sách các network interfaces trên máy
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            List<NetworkInterface> usable = new ArrayList<>();
            for (NetworkInterface nif : interfaces) {
                if (isUsable(nif)) {
                    usable.add(nif);
                }
            }

            // Ưu tiên 1: Card mạng vật lý thật (LAN / Wi-Fi)
            for (NetworkInterface nif : usable) {
                if (!nif.isLoopback() && !isVirtualAdapter(nif)) {
                    return nif;
                }
            }

            // Ưu tiên 2: Card mạng máy ảo (VirtualBox, VMware)
            for (NetworkInterface nif : usable) {
                if (!nif.isLoopback()) {
                    return nif;
                }
            }

            // Ưu tiên 3: Loopback interface (hỗ trợ test local khi offline hoàn toàn)
            for (NetworkInterface nif : usable) {
                if (nif.isLoopback()) {
                    return nif;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static boolean isUsable(NetworkInterface nif) {
        try {
            return nif != null && nif.isUp() && nif.supportsMulticast()
                    && Collections.list(nif.getInetAddresses()).stream().anyMatch(a -> a instanceof Inet4Address);
        } catch (SocketException e) {
            return false;
        }
    }

    private static boolean isVirtualAdapter(NetworkInterface nif) {
        if (nif.isVirtual()) return true;
        String name = (nif.getName() + " " + nif.getDisplayName()).toLowerCase(Locale.ROOT);
        return name.contains("virtual") || name.contains("vmware") || name.contains("vbox")
                || name.contains("tap") || name.contains("vpn") || name.contains("vethernet")
                || name.contains("teredo") || name.contains("wsl");
    }

    @Override
    public void close() {
        leaveGroup();
    }
}
