# Lab 6 — Ứng dụng chat LAN với TCP và UDP Multicast

Ứng dụng Java có giao diện JavaFX cho server và client, nằm trong một project Maven. Server quản lý tài khoản, danh sách online, chat riêng và chuyển file riêng qua TCP. Text phòng chung dùng UDP multicast; thông báo tệp chung dùng UDP broadcast cho cả client trong và ngoài nhóm, nội dung tải bằng TCP trực tiếp tới người gửi.

## 1. Phân biệt kết nối, đăng nhập và tham gia phòng

- **Kết nối** trên màn hình login chỉ kiểm tra IP/cổng có nhận kết nối TCP hay không. Socket kiểm tra được đóng ngay sau đó; thao tác này không đăng nhập, không đưa người dùng vào danh sách online.
- **Đăng nhập / Đăng ký** tạo phiên TCP đã xác thực với server. Đăng ký thành công sẽ vào màn hình chat. Người dùng xuất hiện trong danh sách online nhưng chưa tham gia multicast.
- Bấm **Phòng chung** để xem text nhóm. Chỉ khi bấm **Tham gia phòng** client mới nhận/gửi text multicast. Mọi tệp đều gửi cho tất cả client từ bất kỳ màn hình chat nào; nhận từ lúc đăng nhập, không cần tham gia nhóm.
- **Rời phòng** đóng socket multicast; người dùng vẫn online, chat riêng và nhận/gửi tệp broadcast được.
- **Đăng xuất**, đóng client hoặc mất kết nối server kết thúc phiên TCP, rời multicast và đóng dịch vụ tệp broadcast.

## 2. Chức năng

### Server

- Chọn IPv4 và cổng TCP, khởi động/dừng server; xử lý nhiều kết nối bằng virtual thread.
- `0.0.0.0` nhận kết nối trên các card mạng; `127.0.0.1` chỉ nhận kết nối cùng máy. IP cụ thể phải thuộc máy server.
- Bảng tài khoản gồm tên, mật khẩu rõ, IP, lần đăng nhập/đăng xuất gần nhất và trạng thái online/offline.
- Đếm tổng số tài khoản và hiển thị toàn bộ danh sách trong bảng server.
- Nhật ký đăng ký, đăng nhập, ngắt kết nối, gửi tin/file riêng, làm mới danh sách và lỗi. Có nút xóa nhật ký; nhật ký chỉ giữ trong phiên chạy.
- Dừng server ngắt client và ghi giờ ra. Hoạt động phòng chung không đi qua server nên không được ghi vào nhật ký server.

### Client

- Kiểm tra kết nối server với trạng thái đang kết nối, thành công hoặc thất bại.
- Đăng ký có xác nhận mật khẩu; ô mật khẩu là ô chữ thường nên luôn hiển thị nội dung đang nhập.
- Danh sách online tự cập nhật, có tìm kiếm và làm mới; danh sách không bao gồm chính mình.
- Chat riêng qua server. Mọi thao tác gửi tệp trên giao diện đều gửi cho tất cả client đang đăng nhập trong cùng LAN, kể cả khi đang mở chat riêng.
- Tham gia/rời phòng chung, nhắn tin multicast, nhận thông báo người vào/ra và lọc tin trùng.
- Gửi tệp bằng nút **Tệp cho tất cả**, hoặc kéo thả vào vùng chat; không cần chọn người nhận hay tham gia nhóm. Mỗi lần xử lý tệp đầu tiên được thả.
- File nhận tự lưu, có nút **Mở tệp** và **Thư mục**.
- Bong bóng tin nhắn có tên và giờ; mỗi cuộc trò chuyện giữ lịch sử và bản nháp riêng trong bộ nhớ. Đăng nhập phiên mới xóa lịch sử giao diện.
- **Enter** gửi tin; **Shift+Enter** xuống dòng.

## 3. Kiến trúc và giao thức

```text
Đăng ký / đăng nhập / danh sách online:
Client ── TCP ── Server

Tin nhắn riêng:
Client A ── TCP ── Server ── TCP ── Client B

Tin nhắn nhóm, JOIN, LEAVE:
Client A ── UDP multicast 230.0.0.1:5000 ── Các client đã tham gia

Thông báo tệp chung:
Client gửi ── UDP broadcast <broadcast của LAN>:5001 ── Mọi client đang đăng nhập trong LAN

Nội dung tệp chung:
Client nhận ── TCP trực tiếp tới cổng tạm của client gửi ── Tải file
```

| Thành phần | Cấu hình / giới hạn |
|---|---|
| TCP server | Cổng mặc định `2005`, thay đổi trên giao diện |
| Nhóm multicast | Mặc định `230.0.0.1:5000`, TTL `32` |
| Tệp broadcast | UDP `5001`, broadcast của card kết nối server; đổi bằng `-Dbroadcast.port=5001` trên mọi client |
| Tin riêng | 1–8.000 ký tự, không được chỉ chứa khoảng trắng |
| Tin nhóm | Toàn bộ gói UTF-8 tối đa `4.096 byte`, gồm cả header |
| File riêng và nhóm | Tối đa `20 MiB` (20 × 1024 × 1024 byte), hỗ trợ file rỗng |
| Tên tài khoản | 1–32 chữ cái Unicode, chữ số, `_`, `-`; không có khoảng trắng |
| Mật khẩu | 6–128 ký tự, không được chỉ chứa khoảng trắng |

TCP dùng khung nhị phân `CHT3`: độ dài khung, lgit add .oại lệnh, request ID, các trường chuỗi và dữ liệu file. Các lệnh client gửi là `LOGIN`, `SIGNUP`, `LIST`, `MESSAGE`, `FILE`; server trả `AUTHENTICATED`, `USERS`, `OK`, `ERROR` và chuyển tiếp `MESSAGE`/`FILE`. Đăng xuất đóng socket TCP. Client có luồng đọc riêng và timeout chờ phản hồi; server khóa luồng ghi của mỗi kết nối để tin nhắn và file không xen lẫn.

UDP multicast dùng `JOIN|username`, `LEAVE|username`, `MESSAGE|username|timestamp|content`. UDP broadcast dùng `FILE_OFFER` chứa người gửi, token UUID, cổng TCP tạm, kích thước và tên file mã hóa Base64. Mọi client đăng nhập trong cùng mạng broadcast tự tải và lưu tệp, dù chưa tham gia hoặc đã rời nhóm. Tệp hiển thị ở mọi màn hình trò chuyện, kể cả chat riêng và màn hình chưa chọn người dùng. A đang chat với B mà gửi tệp thì C và mọi client khác cũng nhận; text riêng vẫn chỉ tới B. Token có hiệu lực 10 phút; mỗi client giữ tối đa 32 file chia sẻ chưa hết hạn. Người gửi cần giữ file nguồn và tiếp tục đăng nhập trong lúc người nhận tải.

## 4. Cấu trúc mã nguồn

```text
src/main/java/com/clientserverchat/
  Main.java                         Chọn vai trò server hoặc client
  common/Protocol.java              Khung TCP và kiểm tra tên dữ liệu
  server/
    ServerApp.java                  Ứng dụng JavaFX server
    ServerLauncher.java             Điểm chạy server trong IDE
    core/ChatServer.java             Xác thực và chuyển tin/file riêng
    core/UserRegistry.java           Lưu tài khoản, xác thực, IP và giờ vào/ra
    core/UserInfo.java               Dữ liệu hiển thị bảng người dùng
    ui/ServerView.java               Giao diện quản lý server
    ui/ServerController.java         Điều phối thao tác server
  client/
    ClientApp.java                  Ứng dụng JavaFX client
    ClientLauncher.java             Điểm chạy client trong IDE
    core/Authentication.java         Kiểm tra form và thông báo lỗi
    core/ChatClient.java             Kết nối TCP, tin/file riêng
    core/ChatMessage.java            Sự kiện tin nhắn cho giao diện
    core/MulticastChatService.java   Join/leave và text nhóm
    core/BroadcastFileService.java   Thông báo tệp cho mọi client đăng nhập trong LAN
    core/GroupFileTransfer.java      Chia sẻ và tải file nhóm qua TCP
    ui/ClientView.java               Giao diện login và chat
    ui/ClientController.java         Điều phối TCP, multicast và lịch sử chat
src/main/resources/styles/chat.css  CSS dùng chung
src/test/java/                      Kiểm thử JUnit
```

Các lớp `core` và `common` không phụ thuộc JavaFX. Controller gọi dịch vụ mạng ở luồng nền và cập nhật giao diện bằng `Platform.runLater`.

## 5. Tên phương thức chính để tìm chức năng

Các tên phương thức nghiệp vụ được Việt hóa không dấu để dễ tìm bằng Ctrl+F trong IDE:

| Phương thức | Nằm ở | Chức năng |
|---|---|---|
| `dangNhap(...)` | `client/core/ChatClient.java` | Client mở kết nối TCP và gửi lệnh `LOGIN` tới server. |
| `dangKi(...)` | `client/core/ChatClient.java`, `server/core/UserRegistry.java` | Client gửi lệnh `SIGNUP`; server tạo tài khoản mới và lưu mật khẩu rõ. |
| `dangXuat()` | `client/core/ChatClient.java` | Đóng socket TCP, gỡ client khỏi danh sách online. |
| `guiTinNhan(...)` | `client/core/ChatClient.java` | Gửi tin nhắn riêng qua mô hình Client -> TCP Server -> Client. |
| `guiTep(...)` | `client/core/ChatClient.java` | Gửi file riêng qua TCP server, giới hạn 20 MiB. |
| `lamMoiDanhSachNguoiDung()` | `client/core/ChatClient.java` | Hỏi server danh sách người dùng đang online. |
| `thamGiaPhongMulticast(...)` | `client/core/MulticastChatService.java` | Join nhóm UDP multicast `230.0.0.1:5000` để chat phòng chung. |
| `roiPhongMulticast()` | `client/core/MulticastChatService.java` | Rời nhóm multicast, đóng socket nhận tin nhóm. |
| `guiTinNhanNhom(...)` | `client/core/MulticastChatService.java` | Gửi tin nhắn phòng chung bằng UDP multicast. |
| `guiTep(...)` | `client/core/BroadcastFileService.java` | Phát thông báo tệp qua broadcast cho cả trong/ngoài nhóm, tải nội dung bằng TCP trực tiếp. |
| `kiemTraKetNoiServer()` | `client/ui/ClientController.java` | Nút **Kết nối** trên form login, chỉ kiểm tra IP/cổng TCP, chưa đăng nhập. |
| `dangNhapHoacDangKi()` | `client/ui/ClientController.java` | Nút **Đăng nhập/Đăng ký**, kiểm tra form rồi gọi `dangNhap` hoặc `dangKi`. |
| `khoiDongServer()` | `server/ui/ServerController.java` | Nút **Khởi động**, bind IP/cổng và bắt đầu nhận client TCP. |
| `dungServer()` | `server/ui/ServerController.java` | Nút **Dừng server**, đóng toàn bộ phiên client và ghi giờ ra. |
| `danhSachNguoiDung()` | `server/core/UserRegistry.java` | Snapshot dữ liệu bảng server: tài khoản, IP, giờ vào, giờ ra, trạng thái, mật khẩu rõ. |
| `ghiDangNhap(...)` / `ghiDangXuat(...)` | `server/core/UserRegistry.java` | Cập nhật IP, giờ vào/giờ ra và trạng thái online/offline cho giao diện server. |

## 6. Build và chạy

Cần **JDK 21 trở lên**. Đặt `JAVA_HOME` tới JDK; `java -version` cũng phải là phiên bản phù hợp nếu chạy lệnh `java` trực tiếp. Maven Wrapper có sẵn, lần đầu cần mạng để tải Maven và dependency.

Tại thư mục project trên Windows, có 2 cách chạy bằng terminal.

Cách 1: chạy bằng script có sẵn:

```powershell
.\mvnw.cmd clean package
.\run-server.cmd
```

Trong cửa sổ server, chọn IP/cổng rồi bấm **Khởi động**. Mở terminal khác để chạy client:

```powershell
.\run-client.cmd
```

Cách 2: chạy trực tiếp bằng Maven/Java không dùng script:

```powershell
.\mvnw.cmd clean package
java -jar target/server-client-chat-app.jar server
```

Mở terminal thứ hai trong cùng thư mục project để chạy client:

```powershell
java -jar target/server-client-chat-app.jar client
```

Có thể mở thêm terminal thứ ba, thứ tư... và chạy lại lệnh client để tạo nhiều client:

```powershell
java -jar target/server-client-chat-app.jar client
```

Có thể chạy nhiều client. Hai script tự build nếu chưa có `target/server-client-chat-app.jar`; nếu JAR đã tồn tại, script dùng JAR đó. Sau khi sửa hoặc cập nhật code, chạy `mvnw.cmd package` trước khi mở lại ứng dụng.

Tóm tắt lệnh JAR trực tiếp:

```powershell
java -jar target/server-client-chat-app.jar server
java -jar target/server-client-chat-app.jar client
```

Không truyền vai trò thì `Main` mở client. JAR kèm JavaFX; khi đổi hệ điều hành/kiến trúc, build lại để lấy thư viện native phù hợp.

Trong IntelliJ: Reload Maven, chọn Project SDK và Maven JRE là JDK 21+. Chạy `Main` với argument `server` hoặc `client`, hoặc chạy `ServerLauncher`/`ClientLauncher` trực tiếp. `ChatServer.main` là điểm chạy TCP server bằng console, không có giao diện quản lý.

## 7. Demo trên máy chính và máy ảo

1. Đặt mạng máy ảo ở chế độ **Bridged** và bảo đảm các máy liên lạc được trong cùng LAN.
2. Server trên máy chính dùng IP LAN hoặc `0.0.0.0`, cổng `2005`.
3. Client trên máy ảo nhập **IP LAN của máy chính**. Không dùng `127.0.0.1` để tới server ở máy khác; không dùng `0.0.0.0` làm địa chỉ đích.
4. Có thể chỉ chép JAR mới sang máy ảo có JDK 21+ rồi chạy:

```powershell
java -jar server-client-chat-app.jar client
```

Firewall cần cho phép TCP server (`2005`), UDP multicast (`5000`), UDP broadcast (`5001`) và Java nhận kết nối TCP trên client gửi tệp chung. Cổng tải tệp được hệ điều hành chọn khi đăng nhập. Máy thật và máy ảo cần cùng mạng broadcast (Bridged hoặc Host-only phù hợp); broadcast không đi qua router/NAT.

Nếu có nhiều card mạng/VPN, chọn card bằng VM option trước `-jar`:

```powershell
java -Dmulticast.interface=<ten-card-Java> -jar server-client-chat-app.jar client
```

Có thể dùng `jshell` để xem tên card:

```java
java.net.NetworkInterface.networkInterfaces().forEach(n -> System.out.println(n.getName() + " : " + n.getDisplayName()));
```

Các client phải dùng cùng địa chỉ/cổng nhóm nếu đổi mặc định:

```powershell
java -Dmulticast.group=230.0.0.1 -Dmulticast.port=5000 -jar server-client-chat-app.jar client
```

TCP có thể hoạt động dù multicast bị chặn bởi firewall, VPN, chế độ mạng máy ảo hoặc Wi-Fi AP isolation.

## 8. Dữ liệu và giới hạn

- `data/server/accounts.txt`: tài khoản, mật khẩu rõ, IP và giờ vào/ra gần nhất. Đây là bài lab lập trình mạng nên không dùng băm/mã hóa mật khẩu. Dữ liệu lưu dạng `key=value`:
  - `format=2` để phân biệt với định dạng cũ.
  - `<tài khoản>.password`, `<tài khoản>.ip`, `<tài khoản>.login`, `<tài khoản>.logout` cho từng người dùng.
- Bảng server luôn hiển thị mật khẩu thật của tài khoản đã đăng ký hoặc đọc từ file dữ liệu. Client cũng luôn hiển thị mật khẩu khi nhập vì dùng `TextField`, không dùng `PasswordField`.
- `downloads/<username>/`: file riêng có tiền tố `received_`, file nhóm có tiền tố `group_`, tên duy nhất để tránh ghi đè. Các đường dẫn dữ liệu tính từ thư mục chạy.
- Mở lại server thì tài khoản ban đầu đều offline. Nếu tiến trình bị tắt cưỡng bức, giờ ra có thể chưa được ghi.
- Chưa lưu lịch sử chat xuống đĩa, chưa gửi tin cho người offline, chưa có tạo phòng riêng.
- UDP không có ACK hoặc gửi bù: tin nhóm/thông báo file có thể mất hoặc đảo thứ tự. Tin nhóm của người gửi được hiển thị trước khi gửi; chưa có trạng thái giao nhận. File nhóm tải bằng TCP nhưng thông báo chia sẻ vẫn phụ thuộc UDP.
- `OK` của file riêng chỉ xác nhận server đã chuyển vào kết nối người nhận, chưa xác nhận lưu xuống đĩa. File riêng được giữ trong RAM khi chuyển; file nhóm được đọc theo luồng.
- TCP chưa có TLS; multicast chưa mã hóa/xác thực người gửi. Đây là đồ án thực hành trong LAN, không phải dịch vụ chat triển khai công khai.
- Khi cập nhật, dùng cùng bản JAR mới cho server và client; không trộn client cũ yêu cầu thông tin multicast từ server với server hiện tại.

## 9. Kiểm thử

```powershell
.\mvnw.cmd test
```

JUnit kiểm tra framing/Unicode, đăng ký và mật khẩu, lưu/đọc tài khoản, IP/giờ vào/ra, đăng nhập trùng, tin riêng đồng thời, file hai chiều/file rỗng, người nhận offline, mất kết nối/reconnect, trạng thái form login, phản hồi nút Kết nối, multicast join/leave/rejoin, chống trùng và file nhóm.

Các bài test multicast/broadcast cần card IPv4 và firewall cho phép UDP. Test tệp kiểm tra nhận trong/ngoài nhóm, nhận sau khi rời nhóm, nội dung byte, tệp rỗng và đóng dịch vụ khi đăng xuất. Test giao diện cần môi trường chạy được JavaFX.
