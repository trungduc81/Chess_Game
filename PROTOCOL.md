# Giao thức TCP JSON

Mỗi thông điệp là một JSON UTF-8 trên đúng một dòng. Kích thước tối đa là 64 KiB tính theo byte; kết nối bị đóng ngay khi một frame vượt giới hạn trước ký tự xuống dòng.

```json
{
  "type": "MOVE",
  "requestId": "uuid-cua-yeu-cau",
  "sessionToken": "token-sau-khi-dang-nhap",
  "gameId": "uuid-cua-van-dau",
  "payload": {
    "from": "e2",
    "to": "e4",
    "promotion": null
  }
}
```

- `type`: loại nghiệp vụ.
- `requestId`: ghép request/response và hỗ trợ truy vết.
- `sessionToken`: bắt buộc sau khi đăng nhập; Server không tin username do Client tự gửi.
- `gameId`: bắt buộc với thông điệp thuộc một ván đấu.
- `payload`: dữ liệu riêng của từng loại message.

## Nhóm thông điệp

| Nhóm | Client gửi | Server gửi |
|---|---|---|
| Tài khoản | `REGISTER`, `LOGIN`, `LOGOUT` | `LOGIN_SUCCESS`, `SUCCESS`, `ERROR` |
| Lobby | `CHALLENGE_SEND`, `CHALLENGE_RESPONSE` | `ONLINE_LIST`, `CHALLENGE_RECEIVED`, `CHALLENGE_RESULT` |
| Ván đấu | `LEGAL_MOVES_REQUEST`, `MOVE`, `RESIGN`, `EXIT_GAME`, `REMATCH_RESPONSE` | `GAME_STARTED`, `GAME_STATE`, `LEGAL_MOVES_RESPONSE`, `MOVE_REJECTED`, `GAME_OVER`, `REMATCH_STATUS` |
| Tương tác | `CHAT_SEND`, `DRAW_OFFER`, `DRAW_RESPONSE` | `CHAT_RECEIVED`, `DRAW_RECEIVED`, `DRAW_RESULT` |
| Dữ liệu | `HISTORY_REQUEST`, `MATCH_DETAIL_REQUEST`, `RANKING_REQUEST` | `HISTORY_RESPONSE`, `MATCH_DETAIL_RESPONSE`, `RANKING_RESPONSE` |
| Quản trị | `ADMIN_DASHBOARD_REQUEST`, `ADMIN_USERS_REQUEST`, `ADMIN_LOCK_USER`, `ADMIN_UNLOCK_USER`, `ADMIN_KICK_USER`, `ADMIN_MATCHES_REQUEST` | `ADMIN_DASHBOARD_RESPONSE`, `ADMIN_USERS_RESPONSE`, `ADMIN_MATCHES_RESPONSE`, `ADMIN_ACTION_RESULT`, `ERROR` |
| Kết nối | `PONG` | `PING`, `DISCONNECTED` (Server có thể chủ động ngắt phiên) |

`CHALLENGE_RECEIVED` trả về `challengeId`, `challenger` và `challengerElo` để Client hiển thị đầy đủ lời mời thách đấu.

## Luồng đăng nhập

1. Client gửi `LOGIN` không có token.
2. Server xác thực tài khoản và kiểm tra đăng nhập trùng.
3. Server trả `LOGIN_SUCCESS` có `sessionToken`, ELO và `role` (`PLAYER` hoặc `ADMIN`).
4. `NetworkClient` tự gắn token vào các yêu cầu sau đó.

Ví dụ payload đăng nhập thành công:

```json
{
  "username": "admin",
  "elo": 1200,
  "role": "ADMIN",
  "sessionToken": "uuid-phien-dang-nhap"
}
```

Tài khoản có `account_status=LOCKED` bị từ chối đăng nhập. Admin dùng cùng `LOGIN` và cùng kết nối TCP
với người chơi, nhưng Server chỉ chấp nhận nhóm `ADMIN_*`, `MATCH_DETAIL_REQUEST`, `LOGOUT` và heartbeat
cho phiên có `role=ADMIN`. Người chơi gửi giả thông điệp quản trị sẽ nhận `ERROR`.

## Giao thức quản trị

Tất cả yêu cầu dưới đây phải có `sessionToken` của Admin. Payload của các request chỉ đọc dữ liệu là `{}`.

### Dashboard

- Client gửi `ADMIN_DASHBOARD_REQUEST`.
- Server trả `ADMIN_DASHBOARD_RESPONSE`:

```json
{
  "totalUsers": 24,
  "onlineUsers": 5,
  "activeGames": 2,
  "totalMatches": 128
}
```

`totalUsers` chỉ đếm tài khoản người chơi; `onlineUsers` không tính phiên Admin.

### Quản lý tài khoản

- `ADMIN_USERS_REQUEST` trả `ADMIN_USERS_RESPONSE` là một mảng. Mỗi phần tử có dạng:

```json
{
  "id": 12,
  "username": "player1",
  "elo": 1230,
  "totalMatches": 17,
  "online": true,
  "onlineStatus": "FREE",
  "playerStatus": "FREE",
  "accountStatus": "ACTIVE",
  "role": "PLAYER",
  "createdAt": 1770000000000,
  "lockedReason": null
}
```

- Khóa tài khoản: `ADMIN_LOCK_USER` với payload
  `{ "username": "player1", "reason": "Lý do khóa" }`.
- Mở khóa: `ADMIN_UNLOCK_USER` với payload `{ "username": "player1" }`.
- Ngắt kết nối: `ADMIN_KICK_USER` với payload `{ "username": "player1" }`.

Thao tác thành công trả `ADMIN_ACTION_RESULT`:

```json
{
  "success": true,
  "username": "player1",
  "accountStatus": "LOCKED",
  "message": "Đã khóa tài khoản player1"
}
```

`accountStatus` có trong kết quả khóa/mở khóa và có thể vắng trong kết quả ngắt kết nối.
Admin không được khóa, mở khóa hoặc ngắt kết nối một tài khoản Admin. Khi khóa người chơi đang online,
Server cập nhật CSDL trước rồi đóng kết nối; mọi hành động được ghi vào `admin_audit_logs`.

### Danh sách trận đấu và replay

- `ADMIN_MATCHES_REQUEST` trả `ADMIN_MATCHES_RESPONSE`, là mảng toàn bộ trận đấu gồm các trường
  `id`, `whitePlayer`, `blackPlayer`, `result`, `reason`, `moveCount`, `initialFen`,
  `whiteEloDelta`, `blackEloDelta`, `startedAt`, `endedAt`.
- Payload có thể chứa `sorts`, ví dụ `{ "sorts": [{ "field": "MOVE_COUNT", "direction": "ASC" }] }`.
  Các tiêu chí cho phép: `TIME` (thời gian kết thúc), `MOVE_COUNT` (số nước), `ID` (mã trận);
  hướng sắp xếp là `ASC` hoặc `DESC`, ưu tiên theo thứ tự trong mảng. Tiêu chí không chọn không
  tham gia sắp xếp. Server chỉ dùng tên cột trong danh sách trắng và dùng `ID DESC` để phân định
  khi bằng nhau nếu chưa chọn `ID`. Khi không truyền tiêu chí, mặc định là
  `TIME DESC`, `MOVE_COUNT DESC`, `ID DESC`.
- Admin yêu cầu replay bằng `MATCH_DETAIL_REQUEST` hiện có với payload `{ "matchId": 15 }`.
- Server kiểm tra phiên Admin và trả `MATCH_DETAIL_RESPONSE` có cùng định dạng với replay người chơi:
  `{ "pgn": "1. e4 e5 ...", "moves": [...] }`. Admin được xem mọi trận; người chơi thường chỉ
  được xem trận có chính họ tham gia.

## Luồng nước đi

1. Client gửi `MOVE(from,to,promotion)` cùng `sessionToken` và `gameId`.
2. Server kiểm tra người gửi thuộc ván, trạng thái đang `PLAYING`, mốc nhận trước deadline, đúng lượt và luật cờ.
3. Nếu sai, Server trả `MOVE_REJECTED` chỉ cho người gửi.
4. Nếu đúng, Server phát `GAME_STATE` gồm FEN, lượt mới, deadline mới và lịch sử nước đi tới cả hai Client.
5. Nếu ván kết thúc, Server lưu transaction, cập nhật ELO đúng một lần và phát `GAME_OVER`.

## Trạng thái bàn cờ

```json
{
  "type": "GAME_STATE",
  "gameId": "uuid",
  "payload": {
    "fen": "...",
    "turn": "WHITE",
    "inCheck": false,
    "deadlineEpochMs": 1770000000000,
    "remainingTurnTimeMs": 30000,
    "moves": []
  }
}
```

Client ưu tiên `remainingTurnTimeMs` và dùng đồng hồ đơn điệu cục bộ để hiển thị đếm ngược, tránh phụ thuộc vào độ lệch đồng hồ hệ thống giữa hai máy. `deadlineEpochMs` được giữ lại để tương thích và chẩn đoán. Client không tự quyết định thắng, hòa, hết giờ hoặc nước đi hợp lệ. Server luôn là trọng tài và là nguồn trạng thái chính thức.

`inCheck` cho biết người đang có lượt có bị chiếu hay không. Client dùng cờ này để tô đỏ ô Vua tương ứng; kết quả chiếu bí vẫn do Server quyết định và gửi qua `GAME_OVER`.

Khi người chơi chọn một quân, Client gửi `LEGAL_MOVES_REQUEST(from)`. Server kiểm tra phiên đấu, người chơi, lượt hiện tại và luật cờ rồi trả `LEGAL_MOVES_RESPONSE(from,destinations)`. Client chỉ dùng danh sách này để vẽ gợi ý nước đi; `MOVE` vẫn được Server kiểm tra lại khi thực hiện.

Mỗi phần tử trong `moves` chứa `notation` theo chuẩn SAN, ví dụ `e4`, `Nf3`, `exd5`, `Bxf7+`, `O-O` hoặc `Qh4#`. Khi xem lại trận, `MATCH_DETAIL_RESPONSE` trả về `{ "pgn": "1. e4 e5 2. Nf3 ...", "moves": [...] }`. Backend lưu cả chuỗi PGN của ván đấu và từng FEN sau nước đi để Client vừa hiển thị ký hiệu chuẩn, vừa tua lại chính xác từng thế cờ.

## Heartbeat

Server gửi `PING` định kỳ. `NetworkClient` tự trả `PONG`. Nếu quá thời hạn không nhận được dữ liệu từ Client, Server đóng `ClientConnection`, cập nhật người chơi offline, xử lý ván đang chơi và thông báo cho đối thủ.
