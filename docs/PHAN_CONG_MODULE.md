# Ánh xạ module theo phân công

## Trần Đức Trung - Kết nối, tài khoản và hạ tầng Client-Server

- `config/AppConfig`: cấu hình Server, Client và MySQL.
- `protocol/*`: khung message chung gồm type, requestId, sessionToken, gameId và payload.
- `server/ChessServer`, `server/ClientConnection`: TCP Server, xử lý nhiều Client, đăng ký/đăng nhập/đăng xuất, trạng thái và heartbeat.
- `client/NetworkClient`: kết nối TCP, gửi/nhận JSON và tự phản hồi PING/PONG.
- `client/ChessClientApp`: coordinator nhận message và điều hướng màn hình.
- `client/ui/LoginView`, `client/ui/LobbyView`: giao diện tài khoản và sảnh chờ.
- `security/PasswordHasher`: băm và kiểm tra mật khẩu.

## Nguyễn Hữu Khánh - Phiên đấu, đồng hồ và giao diện trận đấu

- `server/GameSession`: phiên đấu, deadline, kết thúc ván và biểu quyết tái đấu.
- Các handler challenge, chat, hòa, đầu hàng, thoát và tái đấu trong `server/ChessServer`.
- `client/ui/GameView`, `client/ui/BoardView`: bàn cờ, đồng hồ, chat, danh sách nước đi và dialog.
- `server/GameSessionConcurrencyTest`: kiểm thử yêu cầu gần đồng thời và timeout.

## Trịnh Anh Nghĩa - Thuật toán cờ vua và đồng bộ bàn cờ

- Toàn bộ package `chess`: mô hình nước đi, màu quân, kết quả và Chess Engine.
- `ChessBoard` kiểm tra nước đi, nhập thành, phong cấp, bắt tốt qua đường, chiếu, chiếu bí, bí nước và hòa.
- `ChessBoardTest`: unit test luật và trường hợp biên.
- `GAME_STATE` truyền FEN và lịch sử nước đi để đồng bộ hai Client.

## Phan Quang Sáng - Cơ sở dữ liệu, ELO, lịch sử, replay và xếp hạng

- Toàn bộ package `repository`, package `rating` và `database/schema.sql`.
- `ChessRepository.saveMatch`: transaction lưu kết quả, FEN và cập nhật ELO đúng một lần.
- `rating/EloCalculator`: ELO mặc định 1200, K=32.
- Các handler lịch sử, chi tiết trận và bảng xếp hạng trong `ChessServer`.
- Giao diện lịch sử, replay và bảng xếp hạng trong `client/ui/HistoryReplayView`.
