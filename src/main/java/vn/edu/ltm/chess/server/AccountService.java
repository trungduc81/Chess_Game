package vn.edu.ltm.chess.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.MessageType;
import vn.edu.ltm.chess.protocol.NetworkMessage;
import vn.edu.ltm.chess.repository.ChessRepository;
import vn.edu.ltm.chess.repository.UserAccount;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static vn.edu.ltm.chess.server.ServerMessages.*;

/** Tài khoản, phiên đăng nhập, heartbeat và dọn kết nối. */
final class AccountService {
    private final ServerState state;
    private final ChessRepository repository;
    private final ServerMessages messages;
    private final GameService games;
    private final ExecutorService clientExecutor;
    private final long heartbeatTimeoutMillis;

    AccountService(ServerState state, ChessRepository repository, ServerMessages messages,
                   GameService games, ExecutorService clientExecutor, long heartbeatTimeoutMillis) {
        this.state = state;
        this.repository = repository;
        this.messages = messages;
        this.games = games;
        this.clientExecutor = clientExecutor;
        this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
    }

    boolean authenticated(ClientConnection client, NetworkMessage message) {
        if (client.username() == null) {
            error(client, message, "Bạn phải đăng nhập trước");
            return false;
        }
        if (message.sessionToken() == null || !message.sessionToken().equals(client.sessionToken())) {
            error(client, message, "Phiên đăng nhập không hợp lệ");
            return false;
        }
        return true;
    }

    void register(ClientConnection client, NetworkMessage message) throws SQLException {
        if (client.username() != null) {
            error(client, message, "Hãy đăng xuất trước khi đăng ký tài khoản khác");
            return;
        }
        String username = text(message.data(), "username").trim();
        String password = text(message.data(), "password");
        if (!username.matches("[A-Za-z0-9_]{3,40}") || password.length() < 6) {
            error(client, message, "Tên đăng nhập gồm 3-40 ký tự chữ/số/_ và mật khẩu tối thiểu 6 ký tự");
            return;
        }
        if (!repository.register(username, password)) {
            error(client, message, "Tên đăng nhập đã tồn tại");
            return;
        }
        success(client, message, "Đăng ký thành công, ELO khởi tạo là 1200");
    }

    void login(ClientConnection client, NetworkMessage message) throws SQLException {
        if (client.username() != null) {
            error(client, message, "Kết nối này đã đăng nhập");
            return;
        }
        String username = text(message.data(), "username").trim();
        Optional<UserAccount> account = repository.authenticate(username, text(message.data(), "password"));
        if (account.isEmpty()) {
            error(client, message, "Sai tên đăng nhập hoặc mật khẩu");
            return;
        }
        if ("LOCKED".equalsIgnoreCase(account.get().accountStatus())) {
            String reason = account.get().lockedReason();
            error(client, message, reason == null || reason.isBlank()
                    ? "Tài khoản đã bị khóa"
                    : "Tài khoản đã bị khóa: " + reason);
            return;
        }
        String token;
        boolean duplicateLogin;
        synchronized (state.matchmakingLock) {
            duplicateLogin = state.online.containsKey(username);
            if (duplicateLogin) {
                token = null;
            } else {
                token = UUID.randomUUID().toString();
                client.username(username);
                client.role(account.get().role());
                client.sessionToken(token);
                state.online.put(username, client);
                if ("PLAYER".equals(account.get().role())) state.statuses.put(username, PlayerStatus.FREE);
            }
        }
        if (duplicateLogin) {
            error(client, message, "Tài khoản đang đăng nhập ở máy khác");
            return;
        }
        ObjectNode data = JsonProtocol.object().put("username", username)
                .put("elo", account.get().elo()).put("role", account.get().role())
                .put("accountStatus", account.get().accountStatus()).put("sessionToken", token);
        client.send(new NetworkMessage(MessageType.LOGIN_SUCCESS, message.requestId(), token, null, data));
        messages.broadcastOnlineList();
    }

    void logout(ClientConnection client) {
        disconnected(client);
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }

    void heartbeat() {
        long now = System.currentTimeMillis();
        for (ClientConnection connection : List.copyOf(state.online.values())) {
            clientExecutor.submit(() -> {
                if (now - connection.lastSeenAt() > heartbeatTimeoutMillis) {
                    System.err
                            .println("Heartbeat timeout: " + connection.username() + " " + connection.remoteAddress());
                    try {
                        connection.close();
                    } catch (IOException ignored) {
                    }
                } else {
                    connection.send(NetworkMessage.of(MessageType.PING,
                            JsonProtocol.object().put("serverTime", now)));
                }
            });
        }
    }

    void disconnected(ClientConnection client) {
        String username = client.username();
        if (username == null)
            return;
        GameSession game;
        synchronized (state.matchmakingLock) {
            if (!state.online.remove(username, client))
                return;
            state.statuses.remove(username);
            state.challenges.values().removeIf(c -> c.challenger().equals(username) || c.challenged().equals(username));
            game = state.playerGames.get(username);
        }
        if (game != null) {
            games.playerDisconnected(game, username);
        }
        messages.broadcastOnlineList();
    }

    void disconnectByAdmin(String username) {
        ClientConnection target = state.online.get(username);
        if (target == null) return;
        try {
            target.close();
        } catch (IOException ignored) {
        }
        disconnected(target);
    }
}
