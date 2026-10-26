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
| Kết nối | `PONG` | `PING`, `DISCONNECTED` (sự kiện nội bộ Client) |

`CHALLENGE_RECEIVED` trả về `challengeId`, `challenger` và `challengerElo` để Client hiển thị đầy đủ lời mời thách đấu.

## Luồng đăng nhập

1. Client gửi `LOGIN` không có token.
2. Server xác thực tài khoản và kiểm tra đăng nhập trùng.
3. Server trả `LOGIN_SUCCESS` có `sessionToken` và ELO.
4. `NetworkClient` tự gắn token vào các yêu cầu sau đó.

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
