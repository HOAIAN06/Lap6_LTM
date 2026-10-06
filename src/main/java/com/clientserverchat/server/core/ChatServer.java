package com.clientserverchat.server.core;

import com.clientserverchat.common.Protocol;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** TCP server for authentication, online users, private messages and files. */
public final class ChatServer implements AutoCloseable {
    private final ServerSocket listener;
    private final Map<String, Session> clients = new ConcurrentHashMap<>();
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("server-deadlines").factory());
    private volatile boolean closed;
    private final UserRegistry users;
    private final Consumer<String> log;

    public ChatServer(int port, UserRegistry users, Consumer<String> log) throws IOException {
        this("0.0.0.0", port, users, log);
    }

    public ChatServer(String ip, int port, UserRegistry users, Consumer<String> log) throws IOException {
        this.users = users;
        this.log = log;
        InetAddress address = bindAddress(ip);
        listener = new ServerSocket();
        try { listener.bind(new InetSocketAddress(address, port)); }
        catch (IOException | IllegalArgumentException e) { listener.close(); throw e; }
    }

    public int getPort() { return listener.getLocalPort(); }
    public String getAddress() { return listener.getInetAddress().getHostAddress(); }

    private static InetAddress bindAddress(String ip) throws IOException {
        if (ip == null || !ip.trim().matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            throw new IOException("Nhập địa chỉ IPv4 hợp lệ, ví dụ 192.168.1.10 hoặc 0.0.0.0");
        }
        String[] parts = ip.trim().split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            int value = Integer.parseInt(parts[i]);
            if (value > 255) throw new IOException("Mỗi phần của IP phải nằm trong 0–255");
            bytes[i] = (byte) value;
        }
        InetAddress address = InetAddress.getByAddress(bytes);
        if (!address.isAnyLocalAddress() && NetworkInterface.getByInetAddress(address) == null) {
            throw new IOException("IP không thuộc máy server. Chọn IP của máy hoặc 0.0.0.0");
        }
        return address;
    }

    public void start() {
        log.accept("Server đang lắng nghe " + getAddress() + ":" + getPort());
        workers.execute(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept();
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(15_000);
                    Session session = new Session(socket);
                    sessions.add(session);
                    if (closed) session.close();
                    else {
                        try { workers.execute(session::run); }
                        catch (RejectedExecutionException e) { session.close(); }
                    }
                } catch (IOException e) {
                    if (!closed) log.accept("Lỗi chấp nhận kết nối: " + e.getMessage());
                }
            }
        });
    }

    private List<String> onlineNames() {
        return clients.values().stream().filter(s -> s.registered).map(s -> s.username).sorted().toList();
    }

    private void broadcastUsers() {
        // Snapshot inside the output lock prevents older lists overtaking newer updates.
        for (Session session : clients.values()) {
            if (session.registered) {
                try {
                    synchronized (session.out) {
                        session.send(new Protocol.Packet("USERS", 0, onlineNames(), new byte[0]));
                    }
                } catch (IOException e) { session.close(); }
            }
        }
    }

    private final class Session implements AutoCloseable {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        String username;
        volatile boolean registered;
        final AtomicBoolean ended = new AtomicBoolean();
        final String sessionId = UUID.randomUUID().toString();

        Session(Socket socket) throws IOException {
            this.socket = socket;
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        void run() {
            try {
                while (!socket.isClosed()) {
                    Protocol.Packet request = Protocol.read(in);
                    try { handle(request); }
                    catch (IOException | IllegalArgumentException e) {
                        String actor = username;
                        if (actor == null && (request.type().equals("LOGIN") || request.type().equals("SIGNUP"))) {
                            try { actor = Protocol.validName(request.field(0)); }
                            catch (IOException ignored) { /* Invalid names are not copied to the activity log. */ }
                        }
                        log.accept((actor == null ? "Client" : actor) + " (" + socket.getInetAddress().getHostAddress()
                                + ") · " + logText(request.type()) + " thất bại: " + logText(e.getMessage()));
                        send(new Protocol.Packet("ERROR", request.id(), e.getMessage() == null ? "Loi du lieu" : e.getMessage()));
                    }
                }
            } catch (IOException ignored) {
                // EOF or invalid framing terminates only this connection.
            } finally {
                close();
                if (!closed) broadcastUsers();
            }
        }

        void handle(Protocol.Packet request) throws IOException {
            if (request.id() <= 0) throw new IOException("Request ID phai duong");
            if (request.type().equals("LOGIN") || request.type().equals("SIGNUP")) {
                if (username != null) throw new IOException("Da dang nhap");
                String name = Protocol.validName(request.field(0));
                users.authenticate(name, request.field(1), request.type().equals("SIGNUP"));
                if (request.type().equals("SIGNUP")) log.accept(name + " đã đăng ký tài khoản");
                synchronized (this) {
                    if (ended.get() || closed) throw new IOException("Kết nối đã đóng");
                    if (clients.putIfAbsent(name, this) != null) throw new IOException("Tài khoản đang đăng nhập ở nơi khác");
                    username = name;
                    try {
                        users.connected(name, sessionId, socket.getInetAddress().getHostAddress());
                    } catch (IOException e) {
                        clients.remove(name, this);
                        username = null;
                        throw e;
                    }
                    registered = true;
                }
                send(new Protocol.Packet("AUTHENTICATED", request.id(), name));
                log.accept(name + " đã đăng nhập từ " + socket.getInetAddress().getHostAddress());
                socket.setSoTimeout(0);
                broadcastUsers();
                return;
            }
            if (!registered) throw new IOException("Can dang nhap truoc");
            switch (request.type()) {
                case "LIST" -> {
                    send(new Protocol.Packet("USERS", request.id(), onlineNames(), new byte[0]));
                    log.accept(username + " đã làm mới danh sách người dùng");
                }
                case "MESSAGE", "FILE" -> {
                    Session target = clients.get(request.field(0));
                    if (target == null || !target.registered) throw new IOException("Nguoi nhan khong online");
                    String value = request.field(1);
                    if (request.type().equals("FILE")) Protocol.validFilename(value);
                    else if (value.isBlank() || value.length() > 8000) throw new IOException("Tin nhan can 1-8000 ky tu");
                    try {
                        target.send(new Protocol.Packet(request.type(), 0, List.of(username, value), request.data()));
                    } catch (IOException e) {
                        target.close();
                        throw new IOException("Khong gui duoc den nguoi nhan", e);
                    }
                    log.accept(username + (request.type().equals("FILE")
                            ? " đã gửi tệp " + logText(value) + " (" + request.data().length + " byte) cho "
                            : " đã gửi tin nhắn cho ") + target.username);
                    send(new Protocol.Packet("OK", request.id()));
                }
                default -> throw new IOException("Lenh khong duoc ho tro: " + request.type());
            }
        }

        void send(Protocol.Packet packet) throws IOException {
            // Independent reader and writer; a stalled receiver is closed after 30 seconds.
            synchronized (out) {
                if (closed || socket.isClosed()) throw new IOException("Kết nối đã đóng");
                ScheduledFuture<?> timeout;
                try { timeout = deadlines.schedule(this::close, 30, TimeUnit.SECONDS); }
                catch (RejectedExecutionException e) { throw new IOException("Server đang dừng", e); }
                try { Protocol.write(out, packet); }
                finally { timeout.cancel(false); }
            }
        }

        @Override public void close() {
            if (!ended.compareAndSet(false, true)) return;
            try { socket.close(); } catch (IOException ignored) {}
            synchronized (this) {
                registered = false;
                sessions.remove(this);
                if (username != null && clients.remove(username, this)) {
                    try { users.disconnected(username, sessionId); }
                    catch (IOException e) { log.accept("Không lưu được giờ ra: " + e.getMessage()); }
                    log.accept(username + " đã ngắt kết nối");
                }
            }
        }
    }

    private static String logText(String text) {
        if (text == null) return "Lỗi dữ liệu";
        String clean = text.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ");
        return clean.length() > 250 ? clean.substring(0, 250) + "…" : clean;
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) {}
        sessions.forEach(Session::close);
        workers.shutdown();
        deadlines.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
            deadlines.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.TCP_PORT;
        ChatServer server = new ChatServer(port, new UserRegistry(java.nio.file.Path.of("data/server/accounts.properties")), System.out::println);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("TCP server: " + server.getPort());
        new CountDownLatch(1).await();
    }
}
