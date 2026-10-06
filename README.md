# Lab 4 — Ứng dụng chat LAN với TCP và UDP Multicast

Ứng dụng Java có giao diện JavaFX cho server và client, nằm trong một project Maven. Server quản lý tài khoản, danh sách online, chat riêng và chuyển file riêng qua TCP. Phòng chung dùng UDP multicast trực tiếp giữa các client; file nhóm được tải bằng kết nối TCP trực tiếp tới người gửi.

## 1. Phân biệt kết nối, đăng nhập và tham gia phòng

- **Kết nối** trên màn hình login chỉ kiểm tra IP/cổng có nhận kết nối TCP hay không. Socket kiểm tra được đóng ngay sau đó; thao tác này không đăng nhập, không đưa người dùng vào danh sách online.
- **Đăng nhập / Đăng ký** tạo phiên TCP đã xác thực với server. Đăng ký thành công sẽ vào màn hình chat. Người dùng xuất hiện trong danh sách online nhưng chưa tham gia multicast.
- Bấm **Phòng chung** chỉ mở cuộc trò chuyện nhóm. Chỉ khi bấm **Tham gia phòng** client mới join multicast group và nhận tin/file mới của phòng.
- **Rời phòng** đóng socket multicast và dịch vụ chia sẻ file nhóm; phiên TCP vẫn còn, người dùng vẫn online và vẫn chat riêng được.
- **Đăng xuất**, đóng client hoặc mất kết nối server sẽ kết thúc phiên TCP và rời phòng chung.

## 2. Chức năng

### Server

- Chọn IPv4 và cổng TCP, khởi động/dừng server; xử lý nhiều kết nối bằng virtual thread.
- `0.0.0.0` nhận kết nối trên các card mạng; `127.0.0.1` chỉ nhận kết nối cùng máy. IP cụ thể phải thuộc máy server.
- Bảng tài khoản gồm tên, mật khẩu có trong phiên, IP, lần đăng nhập/đăng xuất gần nhất và trạng thái online/offline.
- Đếm tài khoản/online, tìm theo tên hoặc IP, lọc người đang online.
- Nhật ký đăng ký, đăng nhập, ngắt kết nối, gửi tin/file riêng, làm mới danh sách và lỗi. Có nút xóa nhật ký; nhật ký chỉ giữ trong phiên chạy.
- Dừng server ngắt client và ghi giờ ra. Hoạt động phòng chung không đi qua server nên không được ghi vào nhật ký server.

### Client

- Kiểm tra kết nối server với trạng thái đang kết nối, thành công hoặc thất bại.
- Đăng ký có xác nhận mật khẩu; kiểm tra dữ liệu nhập và hiển thị lỗi ngay trên form.
- Danh sách online tự cập nhật, có tìm kiếm và làm mới; danh sách không bao gồm chính mình.
- Chat riêng, gửi file riêng tới người online qua server.
- Tham gia/rời phòng chung, nhắn tin multicast, nhận thông báo người vào/ra và lọc tin trùng.
- Gửi file riêng hoặc nhóm bằng nút **Tệp**, hoặc kéo thả vào vùng chat; mỗi lần xử lý tệp đầu tiên được thả.
- File nhận tự lưu, có nút **Mở tệp** và **Thư mục**.
- Bong bóng tin nhắn có tên và giờ; mỗi cuộc trò chuyện giữ lịch sử và bản nháp riêng trong bộ nhớ. Đăng nhập phiên mới xóa lịch sử giao diện.
- **Enter** gửi tin; **Shift+Enter** xuống dòng.

## 3. Kiến trúc và giao thức

```text
Đăng ký / đăng nhập / danh sách online:
Client ── TCP ── Server

Tin nhắn và file riêng:
Client A ── TCP ── Server ── TCP ── Client B

Tin nhắn nhóm, JOIN, LEAVE, thông báo file:
Client A ── UDP multicast 230.0.0.1:5000 ── Các client đã tham gia

Nội dung file nhóm:
Client nhận ── TCP trực tiếp tới cổng tạm của client gửi ── Tải file
```

| Thành phần | Cấu hình / giới hạn |
|---|---|
| TCP server | Cổng mặc định `2005`, thay đổi trên giao diện |
| Nhóm multicast | Mặc định `230.0.0.1:5000`, TTL `32` |
| Tin riêng | 1–8.000 ký tự, không được chỉ chứa khoảng trắng |
| Tin nhóm | Toàn bộ gói UTF-8 tối đa `4.096 byte`, gồm cả header |
| File riêng và nhóm | Tối đa `20 MiB` (20 × 1024 × 1024 byte), hỗ trợ file rỗng |
| Tên tài khoản | 1–32 chữ cái Unicode, chữ số, `_`, `-`; không có khoảng trắng |
| Mật khẩu | 6–128 ký tự, không được chỉ chứa khoảng trắng |

TCP dùng khung nhị phân `CHT3`: độ dài khung, lgit add .oại lệnh, request ID, các trường chuỗi và dữ liệu file. Các lệnh client gửi là `LOGIN`, `SIGNUP`, `LIST`, `MESSAGE`, `FILE`; server trả `AUTHENTICATED`, `USERS`, `OK`, `ERROR` và chuyển tiếp `MESSAGE`/`FILE`. Đăng xuất đóng socket TCP. Client có luồng đọc riêng và timeout chờ phản hồi; server khóa luồng ghi của mỗi kết nối để tin nhắn và file không xen lẫn.

UDP dùng gói văn bản `JOIN|username`, `LEAVE|username`, `MESSAGE|username|timestamp|content`. File nhóm được thông báo bằng `FILE_OFFER` chứa người gửi, token UUID, cổng TCP tạm, kích thước và tên file mã hóa Base64. Client đang tham gia tự tải và lưu file. Token có hiệu lực 10 phút; mỗi client giữ tối đa 32 file chia sẻ chưa hết hạn. Người gửi cần giữ file nguồn và tiếp tục ở trong phòng trong lúc người nhận tải.

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
    core/MulticastChatService.java   Join/leave, tin nhóm và thông báo file
    core/GroupFileTransfer.java      Chia sẻ và tải file nhóm qua TCP
    ui/ClientView.java               Giao diện login và chat
    ui/ClientController.java         Điều phối TCP, multicast và lịch sử chat
src/main/resources/styles/chat.css  CSS dùng chung
src/test/java/                      Kiểm thử JUnit
```

Các lớp `core` và `common` không phụ thuộc JavaFX. Controller gọi dịch vụ mạng ở luồng nền và cập nhật giao diện bằng `Platform.runLater`.

## 5. Build và chạy

Cần **JDK 21 trở lên**. Đặt `JAVA_HOME` tới JDK; `java -version` cũng phải là phiên bản phù hợp nếu chạy lệnh `java` trực tiếp. Maven Wrapper có sẵn, lần đầu cần mạng để tải Maven và dependency.

Tại thư mục project trên Windows:

```powershell
.\mvnw.cmd clean package
.\run-server.cmd
```

Trong cửa sổ server, chọn IP/cổng rồi bấm **Khởi động**. Mở terminal khác:

```powershell
.\run-client.cmd
```

Có thể chạy nhiều client. Hai script tự build nếu chưa có `target/server-client-chat-app.jar`; nếu JAR đã tồn tại, script dùng JAR đó. Sau khi sửa hoặc cập nhật code, chạy `mvnw.cmd package` trước khi mở lại ứng dụng.

Chạy JAR trực tiếp:

```powershell
java -jar target/server-client-chat-app.jar server
java -jar target/server-client-chat-app.jar client
```

Không truyền vai trò thì `Main` mở client. JAR kèm JavaFX; khi đổi hệ điều hành/kiến trúc, build lại để lấy thư viện native phù hợp.

Trong IntelliJ: Reload Maven, chọn Project SDK và Maven JRE là JDK 21+. Chạy `Main` với argument `server` hoặc `client`, hoặc chạy `ServerLauncher`/`ClientLauncher` trực tiếp. `ChatServer.main` là điểm chạy TCP server bằng console, không có giao diện quản lý.

## 6. Demo trên máy chính và máy ảo

1. Đặt mạng máy ảo ở chế độ **Bridged** và bảo đảm các máy liên lạc được trong cùng LAN.
2. Server trên máy chính dùng IP LAN hoặc `0.0.0.0`, cổng `2005`.
3. Client trên máy ảo nhập **IP LAN của máy chính**. Không dùng `127.0.0.1` để tới server ở máy khác; không dùng `0.0.0.0` làm địa chỉ đích.
4. Có thể chỉ chép JAR mới sang máy ảo có JDK 21+ rồi chạy:

```powershell
java -jar server-client-chat-app.jar client
```

Firewall cần cho phép TCP server (mặc định `2005`), UDP multicast (mặc định `5000`) và Java nhận kết nối TCP trên client gửi file nhóm. Cổng file nhóm được hệ điều hành chọn mỗi lần tham gia phòng, không cố định ở `2005`.

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

## 7. Dữ liệu và giới hạn

- `data/server/accounts.properties`: tài khoản, salt/hash mật khẩu, IP và giờ vào/ra gần nhất. Mật khẩu được băm bằng PBKDF2-HMAC-SHA256, 210.000 vòng; không lưu mật khẩu rõ vào file.
- Bảng server hiển thị mật khẩu thật của tài khoản đã đăng ký hoặc xác thực thành công trong phiên ứng dụng. Tài khoản đọc từ file hiện **Chưa có trong phiên** cho tới khi đăng nhập. Mật khẩu hiển thị chỉ giữ trong bộ nhớ và không gửi trong danh sách online.
- `downloads/<username>/`: file riêng có tiền tố `received_`, file nhóm có tiền tố `group_`, tên duy nhất để tránh ghi đè. Các đường dẫn dữ liệu tính từ thư mục chạy.
- Mở lại server thì tài khoản ban đầu đều offline. Nếu tiến trình bị tắt cưỡng bức, giờ ra có thể chưa được ghi.
- Chưa lưu lịch sử chat xuống đĩa, chưa gửi tin cho người offline, chưa có tạo phòng riêng.
- UDP không có ACK hoặc gửi bù: tin nhóm/thông báo file có thể mất hoặc đảo thứ tự. Tin nhóm của người gửi được hiển thị trước khi gửi; chưa có trạng thái giao nhận. File nhóm tải bằng TCP nhưng thông báo chia sẻ vẫn phụ thuộc UDP.
- `OK` của file riêng chỉ xác nhận server đã chuyển vào kết nối người nhận, chưa xác nhận lưu xuống đĩa. File riêng được giữ trong RAM khi chuyển; file nhóm được đọc theo luồng.
- TCP chưa có TLS; multicast chưa mã hóa/xác thực người gửi. Đây là đồ án thực hành trong LAN, không phải dịch vụ chat triển khai công khai.
- Khi cập nhật, dùng cùng bản JAR mới cho server và client; không trộn client cũ yêu cầu thông tin multicast từ server với server hiện tại.

## 8. Kiểm thử

```powershell
.\mvnw.cmd test
```

JUnit kiểm tra framing/Unicode, đăng ký và mật khẩu, lưu/đọc tài khoản, IP/giờ vào/ra, đăng nhập trùng, tin riêng đồng thời, file hai chiều/file rỗng, người nhận offline, mất kết nối/reconnect, trạng thái form login, phản hồi nút Kết nối, multicast join/leave/rejoin, chống trùng và file nhóm.

Các bài test multicast cần card mạng và firewall cho phép multicast. Test giao diện cần môi trường chạy được JavaFX.
