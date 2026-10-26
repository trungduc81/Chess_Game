# BTL Chess - Lập trình mạng

Dự án Java Maven triển khai hệ thống cờ vua trực tuyến theo mô hình **một TCP Server - nhiều JavaFX Client**. Server là nguồn trạng thái chính thức: xác thực tài khoản, quản lý lobby và phiên đấu, kiểm tra luật, chốt thời gian, lưu MySQL và tính ELO. Client chỉ gửi thao tác và hiển thị dữ liệu Server xác nhận.

## Chức năng đã triển khai

- Đăng ký, đăng nhập, đăng xuất và chặn đăng nhập trùng; tài khoản mới có 1200 ELO.
- Lobby hiển thị tên, ELO và trạng thái đang rỗi/đang bận; Server tự phát danh sách mới khi trạng thái thay đổi.
- Gửi, nhận, chấp nhận, từ chối và tự hết hạn lời thách đấu.
- Người thách đấu cầm đen; người được thách đấu cầm trắng. Tái đấu chỉ bắt đầu khi cả hai đồng ý và màu quân được đổi.
- Server kiểm tra đúng phiên, đúng người, đúng lượt, hạn 30 giây và luật cờ; hỗ trợ nhập thành, phong cấp, bắt tốt qua đường, chiếu, chiếu bí, bí nước, luật 50 nước, lặp thế và thiếu quân chiếu bí.
- Đồng hồ từng lượt do Server quyết định; `MOVE` và `TIMEOUT` được chốt trong cùng khóa để tránh hai kết quả đồng thời.
- Chat trong trận, xin hòa, chấp nhận/từ chối hòa, đầu hàng và thoát trận.
- Heartbeat `PING/PONG` phát hiện client mất kết nối; không thực hiện reconnect theo phạm vi BTL.
- Lưu kết quả, nguyên nhân kết thúc, điểm ván 1/0.5/0, toàn bộ nước đi và FEN vào MySQL trong transaction.
- Tính ELO với K=32; lưu ELO trước/sau và mức thay đổi. `game_uid` duy nhất chống cập nhật kết quả lặp.
- Xem lịch sử, replay với các nút về đầu/lùi/tiến/tới cuối và bảng xếp hạng theo bốn tiêu chí của đề bài.

## Cấu trúc source code

```text
BTL_Chess/
├── database/schema.sql
├── src/main/java/vn/edu/ltm/chess/
│   ├── chess/       Chess Engine chạy tại Server
│   ├── client/      NetworkClient và coordinator JavaFX
│   │   └── ui/      Login, lobby, game, lịch sử/replay và xếp hạng
│   ├── config/      Đọc .env, biến môi trường và system property
│   ├── protocol/    Khung JSON dùng chung
│   ├── rating/      Công thức tính ELO
│   ├── security/    Băm và kiểm tra mật khẩu
│   ├── repository/  JDBC, MySQL, lịch sử và xếp hạng
│   └── server/      TCP Server, ClientConnection và GameSession
├── src/test/java/   Unit test luật cờ, giao thức, ELO và concurrency
├── .vscode/         Cấu hình Run Server/Client trong VS Code
├── .env.example
├── pom.xml
└── PROTOCOL.md
```

## Yêu cầu

- JDK 21.
- Maven 3.9 trở lên.
- MySQL 8.
- VS Code: Extension Pack for Java (nếu chạy bằng nút Run).

## Cấu hình

Sao chép `.env.example` thành `.env`, sau đó điền mật khẩu MySQL:

```powershell
Copy-Item .env.example .env
```

```text
CHESS_SERVER_HOST=127.0.0.1
CHESS_SERVER_PORT=5555
CHESS_DB_URL=jdbc:mysql://localhost:3306/btl_chess?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh
CHESS_DB_USER=root
CHESS_DB_PASSWORD=your_mysql_password
```

Host và port không xuất hiện trên giao diện đăng nhập. Khi chạy client ở máy khác, sửa `CHESS_SERVER_HOST` trong `.env` thành IPv4 của máy Server. Server tự tạo bảng khi khởi động; cũng có thể chạy `database/schema.sql` bằng MySQL Workbench.

## Biên dịch và kiểm thử

```powershell
mvn clean test
```

## Chạy bằng terminal

Mở terminal thứ nhất để chạy Server:

```powershell
mvn exec:java "-Dexec.mainClass=vn.edu.ltm.chess.server.ServerMain"
```

Mỗi Client được mở bằng một terminal riêng:

```powershell
mvn javafx:run
```

Muốn mở Client thứ hai, mở terminal mới và chạy lại `mvn javafx:run`.

## Chạy bằng VS Code

1. Mở đúng thư mục `BTL_Chess` bằng VS Code.
2. Mở **Run and Debug**.
3. Chạy cấu hình **1. Server Main**.
4. Chạy cấu hình **2. Client JavaFX**. Nhấn chạy lại cấu hình này để mở thêm Client.

Server và Client là hai chương trình độc lập nên phải chạy bằng hai tiến trình khác nhau.

## Lớp khởi động

- Server: `vn.edu.ltm.chess.server.ServerMain`
- Client: `vn.edu.ltm.chess.client.ClientMain`

## Quy ước dữ liệu

- Một nước đi trong CSDL là một **ply** (một lượt của một người).
- Mỗi frame NDJSON được giới hạn tối đa 64 KiB theo byte trước khi giải mã UTF-8.
- Điểm kết quả: thắng 1.0, hòa 0.5, thua 0.0.
- Điểm xếp hạng: ELO khởi tạo 1200, K=32.
- Không lưu mật khẩu thô; mật khẩu được băm PBKDF2-HMAC-SHA256 kèm salt.
