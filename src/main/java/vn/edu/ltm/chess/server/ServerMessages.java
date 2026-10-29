package vn.edu.ltm.chess.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.MessageType;
import vn.edu.ltm.chess.protocol.NetworkMessage;
import vn.edu.ltm.chess.repository.ChessRepository;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/** Tạo phản hồi và phát thông báo dùng chung cho các service. */
final class ServerMessages {
    private final ServerState state;
    private final ChessRepository repository;

    ServerMessages(ServerState state, ChessRepository repository) {
        this.state = state;
        this.repository = repository;
    }

    void broadcastOnlineList() {
        ArrayNode list = JsonProtocol.MAPPER.createArrayNode();
        state.online.entrySet().stream()
                .filter(entry -> "PLAYER".equals(entry.getValue().role()))
                .map(Map.Entry::getKey).sorted().forEach(username -> {
            ObjectNode row = JsonProtocol.object().put("username", username)
                    .put("status", state.statuses.getOrDefault(username, PlayerStatus.FREE).name());
            try {
                row.put("elo", repository.elo(username));
            } catch (SQLException ignored) {
                row.put("elo", 1200);
            }
            list.add(row);
        });
        NetworkMessage message = NetworkMessage.of(MessageType.ONLINE_LIST, list);
        state.online.values().stream().filter(connection -> "PLAYER".equals(connection.role()))
                .forEach(connection -> connection.send(message));
    }

    void sendBoth(GameSession game, MessageType type, JsonNode data) {
        ClientConnection white = state.online.get(game.white());
        ClientConnection black = state.online.get(game.black());
        if (white != null)
            white.send(new NetworkMessage(type, UUID.randomUUID().toString(),
                    white.sessionToken(), game.id(), data));
        if (black != null)
            black.send(new NetworkMessage(type, UUID.randomUUID().toString(),
                    black.sessionToken(), game.id(), data));
    }

    static String text(JsonNode data, String field) {
        return data.path(field).asText("");
    }

    static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    static void success(ClientConnection client, NetworkMessage request, String message) {
        client.send(NetworkMessage.reply(MessageType.SUCCESS, request.requestId(),
                JsonProtocol.object().put("message", message)));
    }

    static void error(ClientConnection client, NetworkMessage request, String message) {
        client.send(NetworkMessage.reply(MessageType.ERROR, request.requestId(),
                JsonProtocol.object().put("message", message)));
    }
}
