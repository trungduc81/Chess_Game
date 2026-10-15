package vn.edu.ltm.chess.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.UUID;

/**
 * Khung thông điệp dùng chung cho toàn hệ thống.
 * payload chứa dữ liệu nghiệp vụ riêng của từng loại message.
 */
public record NetworkMessage(MessageType type, String requestId, String sessionToken,
                             String gameId, JsonNode payload) {
    @JsonCreator
    public NetworkMessage(
            @JsonProperty("type") MessageType type,
            @JsonProperty("requestId") String requestId,
            @JsonProperty("sessionToken") String sessionToken,
            @JsonProperty("gameId") String gameId,
            @JsonProperty("payload") JsonNode payload) {
        this.type = type;
        this.requestId = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        this.sessionToken = sessionToken;
        this.gameId = gameId;
        this.payload = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
    }

    public static NetworkMessage of(MessageType type, JsonNode payload) {
        return new NetworkMessage(type, UUID.randomUUID().toString(), null, null, payload);
    }

    public static NetworkMessage reply(MessageType type, String requestId, JsonNode payload) {
        return new NetworkMessage(type, requestId, null, null, payload);
    }

    public NetworkMessage withContext(String token, String currentGameId) {
        return new NetworkMessage(type, requestId,
                sessionToken == null ? token : sessionToken,
                gameId == null ? currentGameId : gameId, payload);
    }

    /** Tên rút gọn để mã xử lý nghiệp vụ dễ đọc. */
    public JsonNode data() { return payload; }
}
