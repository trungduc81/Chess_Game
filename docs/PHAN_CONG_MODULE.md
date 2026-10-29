# Ánh xạ module theo phân công

## Trần Đức Trung - Kết nối, tài khoản và hạ tầng Client-Server

- `config/AppConfig`: cấu hình Server, Client và MySQL.
- `protocol/*`: khung message chung gồm type, requestId, sessionToken, gameId và payload.
- `server/ChessServer`, `server/ClientConnection`: TCP Server, nhiều Client và định tuyến message.
- `server/AccountService`: đăng ký/đăng nhập/đăng xuất, phiên đăng nhập và heartbeat.
- `server/ServerState`, `server/ServerMessages`: trạng thái dùng chung, danh sách online và phản hồi mạng.
- `client/ClientNetworkService`: kết nối/gửi trên luồng nền, trả callback về JavaFX và bỏ phản hồi cũ sau đăng xuất.
- `client/NetworkClient`: kết nối TCP, gửi/nhận JSON và tự phản hồi PING/PONG.
- `client/ChessClientApp`: coordinator nhận message và điều hướng màn hình.
- `client/ui/LoginView`, `client/ui/LobbyView`: giao diện tài khoản và sảnh chờ.
- `security/PasswordHasher`: băm và kiểm tra mật khẩu.

## Nguyễn Hữu Khánh - Phiên đấu, đồng hồ và giao diện trận đấu

- `server/GameSession`: phiên đấu, deadline, kết thúc ván và biểu quyết tái đấu.
- `server/GameService`: các handler thách đấu, chat, hòa, đầu hàng, thoát, tái đấu và timeout.
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
- `server/MatchQueryService`: handler lịch sử, chi tiết trận và bảng xếp hạng.
- `client/ui/ProfileView`, `client/ui/ReplayView`, `client/ui/RankingView`: hồ sơ/lịch sử, replay và xếp hạng.

## Quản trị và kiểm thử tích hợp

- `server/AdminService`: thống kê, quản lý người chơi và truy vấn trận dành cho admin.
- `client/ui/AdminDashboardView`, `AdminUsersView`, `AdminMatchesView`: giao diện quản trị.
- `server/ChessServerIntegrationTest`: kiểm tra luồng TCP + MySQL xuyên các module trên schema tạm.
