# Sequence diagrams

Tai lieu nay mo ta cac luong chinh cua ung dung theo ten ham trong source code.

## 1. Khoi dong server va bind IP

```mermaid
sequenceDiagram
    actor Admin as Nguoi quan tri
    participant View as ServerView
    participant Ctrl as ServerController
    participant Server as ChatServer
    participant Net as NetworkInterface / ServerSocket

    Admin->>View: Nhap IP, port va bam Start
    View->>Ctrl: khoiDongServer()
    Ctrl->>Ctrl: doc ip, port va kiem tra port
    Ctrl->>Server: new ChatServer(ip, port, registry, log)
    Server->>Server: kiemTraIpLangNghe(ip)
    Server->>Net: new ServerSocket()
    Server->>Net: bind(InetSocketAddress(address, port))
    Net-->>Server: Bind thanh cong
    Ctrl->>Server: start()
    Server->>Net: accept() trong worker thread
    Server-->>Ctrl: getAddress(), getPort()
    Ctrl-->>View: showRunning(true, false)

    alt IP khong hop le / khong thuoc may server / port da dung
        Server-->>Ctrl: IOException
        Ctrl-->>View: baoLoi(error)
    end
```

`0.0.0.0` duoc chap nhan trong `kiemTraIpLangNghe()` va bind tat ca interface. Neu nhap IP LAN cu the, server chi bind interface do.

## 2. Ket noi, dang nhap hoac dang ky

```mermaid
sequenceDiagram
    actor User as Nguoi dung
    participant View as ClientView
    participant Ctrl as ClientController
    participant Client as ChatClient
    participant Auth as Authentication
    participant Server as ChatServer
    participant Session as ChatServer.Session
    participant Users as UserRegistry
    participant Protocol as Protocol

    User->>View: Nhap host, port, username, password
    User->>View: Bam Connect / Sign in / Sign up
    View->>Ctrl: kiemTraKetNoiServer() hoac dangNhapHoacDangKi()
    Ctrl->>Auth: docCong() va kiemTraDangNhap()
    Ctrl->>Client: dangNhap() hoac dangKi()
    Client->>Client: ketNoiTaiKhoan()
    Client->>Auth: kiemTraDangNhap()
    Client->>Server: Socket.connect(host, port)
    Server->>Server: start() da accept socket
    Server->>Session: new Session(socket)
    Server->>Session: run()
    Client->>Protocol: write(LOGIN/SIGNUP, id, username, password)
    Session->>Protocol: read(in)
    Session->>Session: xuLy(request)
    Session->>Users: xacThuc(name, password, dangKiMoi)
    Users-->>Session: Thanh cong
    Session->>Users: ghiDangNhap(name, sessionId, ip)
    Session->>Protocol: gui(AUTHENTICATED)
    Session->>Server: phatDanhSachNguoiDung()
    Server-->>Client: AUTHENTICATED va USERS
    Client->>Client: Connection.readLoop()
    Client-->>Ctrl: onConnected(true), onUsers(names)
    Ctrl-->>View: showChat() va hien thi danh sach online

    alt Sai tai khoan / tai khoan dang dang nhap / loi du lieu
        Session-->>Client: ERROR
        Client-->>Ctrl: CompletableFuture loi
        Ctrl-->>View: baoLoi(error)
    end
```

## 3. Lay lai danh sach nguoi dung online

```mermaid
sequenceDiagram
    actor User as Nguoi dung
    participant View as ClientView
    participant Ctrl as ClientController
    participant Client as ChatClient
    participant Connection as ChatClient.Connection
    participant Session as ChatServer.Session
    participant Server as ChatServer

    User->>View: Bam Refresh
    View->>Ctrl: refresh action
    Ctrl->>Client: lamMoiDanhSachNguoiDung()
    Client->>Connection: guiYeuCau("LIST")
    Connection->>Session: Protocol.write(LIST)
    Session->>Session: xuLy(LIST)
    Session->>Server: tenNguoiDungOnline()
    Server-->>Session: danh sach username
    Session-->>Connection: USERS
    Connection->>Connection: readLoop() / deliverUsers()
    Connection-->>Ctrl: onUsers(names)
    Ctrl-->>View: filterUsers() va update()
```

## 4. Chat rieng qua TCP

```mermaid
sequenceDiagram
    actor Sender as Client gui
    participant SenderCtrl as ClientController
    participant SenderClient as ChatClient
    participant SenderConn as Sender Connection
    participant Server as ChatServer
    participant SenderSession as Sender Session
    participant TargetSession as Target Session
    participant TargetClient as Client nhan
    participant TargetCtrl as Target ClientController

    Sender->>SenderCtrl: Bam Send
    SenderCtrl->>SenderCtrl: send()
    SenderCtrl->>SenderClient: guiTinNhan(target, text)
    SenderClient->>SenderConn: guiYeuCau("MESSAGE", target, text)
    SenderConn->>SenderSession: Protocol.write(MESSAGE)
    SenderSession->>Server: xuLy(request)
    Server->>Server: clients.get(target)
    Server->>TargetSession: gui(MESSAGE, [sender, text])
    TargetSession-->>TargetClient: Protocol packet MESSAGE
    TargetClient->>TargetClient: Connection.readLoop()
    TargetClient-->>TargetCtrl: onChat(ChatMessage)
    TargetCtrl-->>TargetCtrl: luu vao conversations va render()
    Server-->>SenderSession: OK
    SenderSession-->>SenderConn: OK
    SenderClient-->>SenderCtrl: onChat(ChatMessage outgoing)
    SenderCtrl-->>SenderCtrl: render tin nhan cua nguoi gui
```

## 5. Gui file rieng qua TCP

```mermaid
sequenceDiagram
    actor Sender as Client gui
    participant Ctrl as ClientController
    participant Client as ChatClient
    participant Conn as Sender Connection
    participant Server as ChatServer
    participant Target as Target Session
    participant Receiver as Client nhan

    Sender->>Ctrl: Chon file va guiTep(file)
    Ctrl->>Client: guiTep(target, file)
    Client->>Client: validFilename() va doc bytes
    Client->>Conn: guiYeuCau("FILE", data, target, fileName)
    Conn->>Server: Protocol.write(FILE)
    Server->>Server: Session.xuLy(FILE)
    Server->>Target: gui(FILE, [sender, fileName], data)
    Target-->>Receiver: FILE packet
    Receiver->>Receiver: Connection.nhanTep(packet)
    Receiver->>Receiver: luu vao downloads/<username>
    Receiver-->>Receiver: onChat(ChatMessage file)
    Server-->>Conn: OK
    Conn-->>Ctrl: hoan tat gui file
```

## 6. Tham gia phong va chat nhom qua UDP multicast

```mermaid
sequenceDiagram
    actor User as Nguoi dung
    participant Ctrl as ClientController
    participant Client as ChatClient
    participant Mcast as MulticastChatService
    participant Card as NetworkInterface
    participant Group as Multicast group 230.0.0.1:5000
    participant Other as Cac client trong phong

    User->>Ctrl: Bam Group room / Join
    Ctrl->>Client: layDiaChiMayClient()
    Client-->>Ctrl: local TCP address
    Ctrl->>Mcast: thamGiaPhongMulticast(username, localTcpAddress)
    Mcast->>Mcast: kiem tra groupIp va groupPort
    Mcast->>Card: chonCardMang(localTcpAddress)
    Card-->>Mcast: networkInterface
    Mcast->>Mcast: bind MulticastSocket va joinGroup()
    Mcast->>Mcast: new GroupFileTransfer()
    Mcast->>Mcast: start langNgheMulticast() thread
    Mcast->>Group: JOIN|username
    Mcast-->>Ctrl: onGroupStateChanged(true)
    Other-->>Mcast: JOIN / LEAVE / MESSAGE datagram
    Mcast->>Mcast: xuLyGoiTinMulticast(payload)
    Mcast-->>Ctrl: onSystemNotice() hoac onMessageReceived()
    Ctrl-->>User: Hien thi tin nhom

    User->>Ctrl: Gui tin nhom
    Ctrl->>Mcast: guiTinNhanNhom(message)
    Mcast->>Group: MESSAGE|username|timestamp|content
```

## 7. Gui tep cho tat ca: UDP broadcast offer + TCP noi dung

```mermaid
sequenceDiagram
    actor Sender as Client gui
    participant Ctrl as ClientController
    participant Mcast as BroadcastFileService gui
    participant FileSender as GroupFileTransfer gui
    participant Group as LAN broadcast UDP 5001
    participant ReceiverMcast as GroupFileTransfer nhan
    participant ReceiverFile as GroupFileTransfer taiTep
    participant Receiver as Client nhan

    Note over Ctrl,Receiver: Bat nghe broadcast tu luc dang nhap, khong can tham gia nhom
    Sender->>Ctrl: Chon Tep cho tat ca trong Phong chung
    Ctrl->>Mcast: guiTep(file)
    Mcast->>FileSender: taoThongBaoChiaSe(file)
    FileSender->>FileSender: tao token, luu Shared va mo listener port tam
    FileSender-->>Mcast: FILE_OFFER|sender|token|port|size|name
    Mcast->>Group: gui FILE_OFFER qua UDP broadcast
    Group-->>ReceiverMcast: DatagramPacket (ca trong va ngoai nhom)
    ReceiverMcast->>ReceiverMcast: nhanThongBaoChiaSe(payload, senderAddress)
    ReceiverMcast->>ReceiverFile: taiTep(address, port, token, ...)
    ReceiverFile->>FileSender: Socket.connect(address, port)
    ReceiverFile->>FileSender: gui token
    FileSender->>FileSender: phucVuTaiTep(socket)
    FileSender-->>ReceiverFile: file size + file bytes qua TCP
    ReceiverFile->>Receiver: luu vao downloads/<username>
    Receiver-->>Receiver: onMessage(ChatMessage file)
```

## 8. Dung server va dong ket noi

```mermaid
sequenceDiagram
    actor User as Nguoi dung / Quan tri
    participant Ctrl as ServerController hoac ClientController
    participant Server as ChatServer
    participant Session as ChatServer.Session
    participant Client as ChatClient
    participant Conn as ChatClient.Connection
    participant Mcast as MulticastChatService

    User->>Ctrl: Bam Stop server / dong ung dung / Logout
    alt Dung server
        Ctrl->>Server: close()
        Server->>Server: closed = true
        Server->>Server: listener.close()
        Server->>Session: close() cho tung session
        Session->>Session: users.ghiDangXuat()
        Server->>Server: shutdown workers va deadlines
    else Client logout / dong ung dung
        Ctrl->>Mcast: roiPhongMulticast()
        Mcast->>Mcast: gui LEAVE, leaveGroup(), close socket
        Ctrl->>Client: dangXuat() / close()
        Client->>Conn: close()
        Conn-->>Server: TCP EOF / socket closed
        Server->>Session: close()
        Session->>Session: xoa client va phatDanhSachNguoiDung()
        Conn-->>Ctrl: onConnected(false), onUsers(empty)
    end
```

## Bang tom tat chuc nang

| Chuc nang | UI/controller | Lop xu ly chinh | Kieu mang | Packet/chuyen tiep |
|---|---|---|---|---|
| Khoi dong server | `ServerController.khoiDongServer()` | `ChatServer` | TCP listen | `bind(IP, port)` |
| Kiem tra IP lang nghe | `ServerController.khoiDongServer()` | `ChatServer.kiemTraIpLangNghe()` | Local network | IP cu the hoac `0.0.0.0` |
| Dang nhap | `ClientController.dangNhapHoacDangKi()` | `ChatClient`, `ChatServer.Session` | TCP | `LOGIN` |
| Dang ky | `ClientController.dangNhapHoacDangKi()` | `ChatClient`, `UserRegistry` | TCP | `SIGNUP` |
| Danh sach online | `ClientController` refresh | `ChatClient`, `ChatServer` | TCP | `LIST`, `USERS` |
| Chat rieng | `ClientController.send()` | `ChatClient`, `Session.xuLy()` | TCP | `MESSAGE` |
| File rieng | `ClientController.guiTep()` | `ChatClient`, `Session.xuLy()` | TCP | `FILE` + bytes |
| Join phong nhom | `ClientController.thamGiaPhongMulticast()` | `MulticastChatService` | UDP multicast | `JOIN` |
| Chat nhom | `ClientController.send()` | `MulticastChatService` | UDP multicast | `MESSAGE` |
| Tep cho tat ca | `ClientController.guiTep()` | `BroadcastFileService`, `GroupFileTransfer` | UDP broadcast + TCP data | `FILE_OFFER` + token |
| Dung/thoat | `ServerController.dungServer()` / `ClientController.close()` | `close()`, `roiPhongMulticast()` | TCP + UDP | `LEAVE`, dong socket |
