package vn.edu.ltm.chess.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.MessageType;
import vn.edu.ltm.chess.protocol.NetworkMessage;
import vn.edu.ltm.chess.repository.AdminDashboardStats;
import vn.edu.ltm.chess.repository.AdminUserRow;
import vn.edu.ltm.chess.repository.ChessRepository;
import vn.edu.ltm.chess.repository.MatchSort;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static vn.edu.ltm.chess.server.ServerMessages.*;

/** Thống kê và quản trị người chơi, khóa tài khoản, ngắt kết nối và tra cứu trận. */
final class AdminService {
    private final ServerState state;
    private final ChessRepository repository;
    private final AccountService accounts;

    AdminService(ServerState state, ChessRepository repository, AccountService accounts) {
        this.state = state;
        this.repository = repository;
        this.accounts = accounts;
    }

    void adminDashboard(ClientConnection client, NetworkMessage message) throws SQLException {
        if (!authenticatedAdmin(client, message)) return;
        AdminDashboardStats stats = repository.adminDashboardStats();
        long onlineUsers = state.online.values().stream()
                .filter(connection -> "PLAYER".equals(connection.role())).count();
        int activeGames = new HashSet<>(state.playerGames.values()).size();
        ObjectNode data = JsonProtocol.object()
                .put("totalUsers", stats.totalUsers())
                .put("onlineUsers", onlineUsers)
                .put("activeGames", activeGames)
                .put("totalMatches", stats.totalMatches());
        client.send(NetworkMessage.reply(MessageType.ADMIN_DASHBOARD_RESPONSE,
                message.requestId(), data));
    }

    void adminUsers(ClientConnection client, NetworkMessage message) throws SQLException {
        if (!authenticatedAdmin(client, message)) return;
        ArrayNode rows = JsonProtocol.MAPPER.createArrayNode();
        for (AdminUserRow user : repository.adminUsers()) {
            ClientConnection active = state.online.get(user.username());
            boolean isOnline = active != null;
            String onlineStatus = !isOnline ? "OFFLINE"
                    : "PLAYER".equals(user.role())
                            ? state.statuses.getOrDefault(user.username(), PlayerStatus.FREE).name()
                            : "ONLINE";
            ObjectNode row = JsonProtocol.object()
                    .put("id", user.id()).put("username", user.username())
                    .put("elo", user.elo()).put("totalMatches", user.totalMatches())
                    .put("online", isOnline).put("onlineStatus", onlineStatus)
                    .put("playerStatus", onlineStatus)
                    .put("accountStatus", user.accountStatus()).put("role", user.role())
                    .put("createdAt", user.createdAt());
            if (user.lockedReason() == null) row.putNull("lockedReason");
            else row.put("lockedReason", user.lockedReason());
            rows.add(row);
        }
        client.send(NetworkMessage.reply(MessageType.ADMIN_USERS_RESPONSE,
                message.requestId(), rows));
    }

    void adminAccountStatus(ClientConnection client, NetworkMessage message,
                                    boolean lock) throws SQLException {
        if (!authenticatedAdmin(client, message)) return;
        String targetUsername = text(message.data(), "username").trim();
        String reason = text(message.data(), "reason").trim();
        if (targetUsername.isBlank()) {
            error(client, message, "Thiếu tên tài khoản cần xử lý");
            return;
        }
        String status = lock ? "LOCKED" : "ACTIVE";
        String action = lock ? "LOCK_USER" : "UNLOCK_USER";
        if (!repository.setAccountStatus(client.username(), targetUsername, status,
                lock ? reason : null, action)) {
            error(client, message, "Không thể xử lý tài khoản này; tài khoản Admin được bảo vệ");
            return;
        }
        ObjectNode result = JsonProtocol.object().put("success", true)
                .put("username", targetUsername).put("accountStatus", status)
                .put("message", lock ? "Đã khóa tài khoản " + targetUsername
                        : "Đã mở khóa tài khoản " + targetUsername);
        client.send(NetworkMessage.reply(MessageType.ADMIN_ACTION_RESULT,
                message.requestId(), result));
        if (lock) accounts.disconnectByAdmin(targetUsername);
    }

    void adminKickUser(ClientConnection client, NetworkMessage message) throws SQLException {
        if (!authenticatedAdmin(client, message)) return;
        String targetUsername = text(message.data(), "username").trim();
        ClientConnection target = state.online.get(targetUsername);
        if (target == null || !"PLAYER".equals(target.role())
                || !repository.logAdminAction(client.username(), targetUsername,
                        "KICK_USER", "Ngắt kết nối bởi quản trị viên")) {
            error(client, message, "Người chơi không online hoặc không thể bị ngắt kết nối");
            return;
        }
        client.send(NetworkMessage.reply(MessageType.ADMIN_ACTION_RESULT, message.requestId(),
                JsonProtocol.object().put("success", true).put("username", targetUsername)
                        .put("message", "Đã ngắt kết nối " + targetUsername)));
        accounts.disconnectByAdmin(targetUsername);
    }

    void adminMatches(ClientConnection client, NetworkMessage message) throws SQLException {
        if (!authenticatedAdmin(client, message)) return;
        client.send(NetworkMessage.reply(MessageType.ADMIN_MATCHES_RESPONSE,
                message.requestId(), JsonProtocol.value(repository.adminMatches(matchSorts(message.data())))));
    }

    private boolean authenticatedAdmin(ClientConnection client, NetworkMessage message) {
        if (!"ADMIN".equals(client.role())) {
            error(client, message, "Bạn không có quyền quản trị hệ thống");
            return false;
        }
        return true;
    }

    static boolean adminMessage(MessageType type) {
        return switch (type) {
            case ADMIN_DASHBOARD_REQUEST, ADMIN_USERS_REQUEST, ADMIN_LOCK_USER,
                    ADMIN_UNLOCK_USER, ADMIN_KICK_USER, ADMIN_MATCHES_REQUEST -> true;
            default -> false;
        };
    }

    private static List<MatchSort> matchSorts(JsonNode data) {
        List<MatchSort> sorts = new ArrayList<>();
        for (JsonNode item : data.path("sorts")) {
            try {
                var field = MatchSort.Field.valueOf(
                        item.path("field").asText().toUpperCase(Locale.ROOT));
                sorts.add(new MatchSort(field,
                        "ASC".equalsIgnoreCase(item.path("direction").asText())));
            } catch (IllegalArgumentException ignored) {
                // Chỉ chấp nhận tên cột trong danh sách trắng.
            }
        }
        return sorts;
    }
}
