# Giao thức TCP JSON

Mỗi thông điệp là một JSON UTF-8 trên đúng một dòng. Kích thước tối đa là 64 KiB.

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
| Ván đấu | `MOVE`, `RESIGN`, `EXIT_GAME`, `REMATCH_RESPONSE` | `GAME_STARTED`, `GAME_STATE`, `MOVE_REJECTED`, `GAME_OVER`, `REMATCH_STATUS` |
| Tương tác | `CHAT_SEND`, `DRAW_OFFER`, `DRAW_RESPONSE` | `CHAT_RECEIVED`, `DRAW_RECEIVED`, `DRAW_RESULT` |
| Dữ liệu | `HISTORY_REQUEST`, `MATCH_DETAIL_REQUEST`, `RANKING_REQUEST` | `HISTORY_RESPONSE`, `MATCH_DETAIL_RESPONSE`, `RANKING_RESPONSE` |
| Kết nối | `PONG` | `PING`, `DISCONNECTED` (sự kiện nội bộ Client) |

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
    "deadlineEpochMs": 1770000000000,
    "moves": []
  }
}
```

Client không tự quyết định thắng, hòa, hết giờ hoặc nước đi hợp lệ. Server luôn là trọng tài và là nguồn trạng thái chính thức.

## Heartbeat

Server gửi `PING` định kỳ. `NetworkClient` tự trả `PONG`. Nếu quá thời hạn không nhận được dữ liệu từ Client, Server đóng `ClientConnection`, cập nhật người chơi offline, xử lý ván đang chơi và thông báo cho đối thủ.
