# Kiến trúc hệ thống

```text
JavaFX Client 1 ─┐
JavaFX Client 2 ─┼── TCP + JSON UTF-8 ── ChessServer (định tuyến)
JavaFX Client N ─┘                         ├── AccountService
                                          ├── GameService → GameSession → ChessBoard
                                          ├── AdminService
                                          └── MatchQueryService
                                                 │
                                          ChessRepository → JDBC → MySQL
```

## Client Layer

`ChessClientApp` điều hướng và phân phối message tới các View. Giao diện được tách thành
`LoginView`, `LobbyView`, `GameView`, `ProfileView`, `RankingView`, `ReplayView` và các View admin.
Client không tự xác nhận luật, kết quả hoặc thời gian.

`ClientNetworkService` quản lý kết nối và một hàng đợi gửi trên luồng nền. `NetworkClient` chỉ
làm TCP/JSON, giữ luồng đọc riêng và trả lời heartbeat. Hai lớp này không import JavaFX.
`ChessClientApp` truyền `Platform::runLater` vào service để mọi phản hồi/lỗi được chuyển về
luồng JavaFX trước khi cập nhật UI.

```text
Luồng JavaFX: thao tác → ClientNetworkService.authenticate/send (đưa vào hàng đợi)
Luồng gửi:   kết nối Socket → write/flush theo đúng thứ tự yêu cầu
Luồng đọc:   nhận JSON → cập nhật sessionToken/gameId → callback
Luồng JavaFX: Platform.runLater → ChessClientApp → cập nhật View
```

Mỗi phiên client có một số thế hệ (`generation`). Đăng xuất hoặc mất kết nối sẽ tăng số này:
tác vụ chưa gửi và callback thuộc phiên cũ bị bỏ qua. Socket được đóng ở luồng nền. Khi thoát
ứng dụng, service hủy hiệu lực phiên, đóng Socket và dừng executor. Không tự reconnect.

## Server Application Layer

Chỉ có **một `ServerMain`, một tiến trình server và một cổng TCP**. Các lớp service là các
đối tượng Java trong cùng tiến trình, không phải microservices hoặc máy chủ riêng.

| Thành phần | Trách nhiệm |
| --- | --- |
| `ChessServer` | Khởi tạo service, mở ServerSocket, nhận client, lên lịch và định tuyến message |
| `ClientConnection` | Đọc/ghi một Socket và cập nhật thời điểm nhận dữ liệu |
| `AccountService` | Đăng ký/đăng nhập/đăng xuất, xác thực token, chống đăng nhập trùng, heartbeat và mất kết nối |
| `GameService` | Thách đấu, nước đi, đồng hồ, chat, hòa, đầu hàng, kết thúc và tái đấu |
| `AdminService` | Quyền admin, thống kê, danh sách người chơi, khóa/mở khóa, ngắt kết nối và lọc trận |
| `MatchQueryService` | Lịch sử, chi tiết replay/PGN và bảng xếp hạng người chơi |
| `ServerState` | Một bộ maps dùng chung: kết nối, trạng thái, lời mời và phiên đấu; cùng khóa ghép trận |
| `ServerMessages` | Tạo phản hồi, phát danh sách online và gửi dữ liệu tới hai đối thủ; tránh mã lặp giữa các service |
| `GameSession` | Trạng thái và khóa của một ván, deadline, kết thúc đúng một lần và biểu quyết tái đấu |
| `ChessBoard` | Luật cờ độc lập với Socket/UI |

`AccountService` gọi `GameService` để kết thúc ván khi có người mất kết nối. `AdminService`
gọi `AccountService` để dọn phiên khi khóa/ngắt kết nối. Các service dùng chung `ServerState`
và `ServerMessages`; không tạo bản sao maps, không tạo vòng phụ thuộc giữa các service.

Khóa `ServerState.matchmakingLock` giữ nguyên phạm vi kiểm tra-cập nhật đăng nhập/ghép cặp.
`GameSession` tiếp tục khóa trạng thái theo từng ván để chốt nước đi và timeout. Scheduler
chỉ chốt timeout rồi chuyển lưu CSDL và Socket I/O sang virtual-thread executor.

## Data Layer

`ChessRepository` là cổng truy cập duy nhất tới MySQL. Kết quả, nước đi và cập nhật ELO được ghi trong một transaction. `game_uid` là khóa idempotency để một ván không bị cộng ELO hai lần.

Đợt tách service không thay đổi SQL, schema, công thức ELO, transaction hoặc định dạng message.

## Luồng chính

1. Client kết nối, đăng nhập và nhận `sessionToken`.
2. Server phát lobby; người chơi gửi thách đấu và Server tạo `GameSession` khi đối thủ chấp nhận.
3. Client gửi `MOVE`; Server kiểm tra token, gameId, deadline và Chess Engine rồi phát `GAME_STATE`.
4. Khi ván kết thúc, Server lưu transaction, cập nhật ELO, phát `GAME_OVER` và điều phối tái đấu.
5. Client có thể yêu cầu lịch sử, replay hoặc bảng xếp hạng; Data Layer truy vấn và trả dữ liệu qua TCP.

## Kiểm thử

`mvn test` chạy unit test và test mạng bằng TCP localhost (không cần MySQL cho các test mặc định).
`ClientNetworkServiceTest` kiểm tra kết nối bất đồng bộ, thứ tự gửi, heartbeat và loại bỏ callback
cũ khi đăng xuất. `GameSessionConcurrencyTest` kiểm tra tranh chấp nước đi/timeout/tái đấu.

Để chạy thêm test tích hợp với MySQL đã cấu hình trong `.env`:

```powershell
mvn "-Dchess.integration=true" test
```

`ChessServerIntegrationTest` tạo schema `btl_chess_test_<UUID>` riêng và xóa trong `finally`.
Tài khoản MySQL chạy test cần quyền tạo/xóa schema test. Test chạy server trên cổng tạm,
kiểm tra đăng ký, đăng nhập trùng, phân quyền, thách đấu, nước đi, chat, hòa, tái đấu đổi màu,
đầu hàng, ELO, lịch sử, replay, xếp hạng và quản trị. Không sử dụng dữ liệu của `btl_chess`.
