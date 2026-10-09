package com.clientserverchat.client.core;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.function.Consumer;

/** Phát thông báo tệp qua UDP broadcast; từng client tải nội dung qua TCP. */
public final class BroadcastFileService implements AutoCloseable {
    public static final int DEFAULT_PORT = 5001;
    private static final int MAX_PACKET_BYTES = 4096;
    private final DatagramSocket receiver;
    private final DatagramSocket sender;
    private final InetAddress broadcast;
    private final int port;
    private final String username;
    private final GroupFileTransfer files;
    private final Consumer<ChatMessage> onMessage;

    public BroadcastFileService(String username, InetAddress localAddress,
                                Consumer<ChatMessage> onMessage, Consumer<String> onError) throws IOException {
        this(username, localAddress, Integer.getInteger("broadcast.port", DEFAULT_PORT), onMessage, onError);
    }

    BroadcastFileService(String username, InetAddress localAddress, int port,
                         Consumer<ChatMessage> onMessage, Consumer<String> onError) throws IOException {
        com.clientserverchat.common.Protocol.validName(username);
        this.username = username;
        this.port = port;
        this.onMessage = onMessage;
        InetAddress destination = InetAddress.getByName("255.255.255.255");
        NetworkInterface card = localAddress == null ? null : NetworkInterface.getByInetAddress(localAddress);
        if (card != null) {
            for (InterfaceAddress address : card.getInterfaceAddresses()) {
                if (address.getAddress().equals(localAddress) && address.getBroadcast() != null) {
                    destination = address.getBroadcast();
                    break;
                }
            }
        }
        broadcast = destination;
        receiver = new DatagramSocket(null);
        DatagramSocket sending = null;
        GroupFileTransfer transfer = null;
        try {
            receiver.setReuseAddress(true);
            receiver.setBroadcast(true);
            receiver.bind(new InetSocketAddress(port));
            // Dùng card của kết nối TCP để broadcast đi đúng LAN/mạng máy ảo.
            sending = new DatagramSocket(new InetSocketAddress(
                    localAddress != null && !localAddress.isLoopbackAddress() ? localAddress : null, 0));
            sending.setBroadcast(true);
            transfer = new GroupFileTransfer(username, onMessage, onError);
        } catch (IOException | RuntimeException e) {
            receiver.close();
            if (sending != null) sending.close();
            if (transfer != null) transfer.close();
            throw e;
        }
        sender = sending;
        files = transfer;
        Thread.ofPlatform().daemon(true).name("udp-broadcast-files-" + username).start(() -> {
            byte[] buffer = new byte[MAX_PACKET_BYTES];
            while (!receiver.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    receiver.receive(packet);
                    String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    if (payload.startsWith("FILE_OFFER|")) files.nhanThongBaoChiaSe(payload, packet.getAddress());
                } catch (IOException e) {
                    if (!receiver.isClosed()) onError.accept("Lỗi nhận tệp broadcast: " + e.getMessage());
                    break;
                }
            }
        });
    }

    public synchronized void guiTep(File file) throws IOException {
        if (sender.isClosed()) throw new IOException("Dịch vụ tệp broadcast đã đóng.");
        byte[] payload = files.taoThongBaoChiaSe(file).getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_PACKET_BYTES) throw new IOException("Thông báo tệp quá dài.");
        sender.send(new DatagramPacket(payload, payload.length, broadcast, port));
        onMessage.accept(new ChatMessage(ChatMessage.GROUP, username, file.getName(), Instant.now(), true, true));
    }

    @Override public synchronized void close() {
        receiver.close();
        sender.close();
        files.close();
    }
}
