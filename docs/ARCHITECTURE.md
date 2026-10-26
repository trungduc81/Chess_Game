# Kiến trúc hệ thống

```text
JavaFX Client 1 ─┐
JavaFX Client 2 ─┼── TCP + JSON UTF-8 ── ChessServer ── JDBC ── MySQL
JavaFX Client N ─┘                         │
                                          ├── Tài khoản và lobby
                                          ├── GameSession và đồng hồ
                                          ├── Chess Engine
                                          └── Lịch sử, ELO, replay, xếp hạng
```

## Client Layer

`ChessClientApp` là coordinator điều hướng và phân phối message. Các lớp `LoginView`, `LobbyView`, `GameView` và `HistoryReplayView` dựng giao diện và xử lý sự kiện cục bộ. `NetworkClient` duy trì TCP, đóng gói/giải mã thông điệp và tự trả lời heartbeat. Client không tự xác nhận luật, kết quả hoặc thời gian.

## Server Application Layer

`ChessServer` nhận nhiều kết nối bằng virtual thread, xác thực khung message và định tuyến nghiệp vụ. `ClientConnection` đại diện cho một socket. Khóa `matchmakingLock` bảo vệ các chuỗi kiểm tra-cập nhật ghép cặp, còn `GameSession` khóa trạng thái theo từng ván để chốt chính xác nước đi và timeout. Scheduler chỉ chốt timeout rồi chuyển lưu CSDL và Socket I/O sang virtual-thread executor. Package `chess` là Chess Engine độc lập với Socket/UI.

## Data Layer

`ChessRepository` là cổng truy cập duy nhất tới MySQL. Kết quả, nước đi và cập nhật ELO được ghi trong một transaction. `game_uid` là khóa idempotency để một ván không bị cộng ELO hai lần.

## Luồng chính

1. Client kết nối, đăng nhập và nhận `sessionToken`.
2. Server phát lobby; người chơi gửi thách đấu và Server tạo `GameSession` khi đối thủ chấp nhận.
3. Client gửi `MOVE`; Server kiểm tra token, gameId, deadline và Chess Engine rồi phát `GAME_STATE`.
4. Khi ván kết thúc, Server lưu transaction, cập nhật ELO, phát `GAME_OVER` và điều phối tái đấu.
5. Client có thể yêu cầu lịch sử, replay hoặc bảng xếp hạng; Data Layer truy vấn và trả dữ liệu qua TCP.
