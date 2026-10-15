package vn.edu.ltm.chess.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class JsonProtocol {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonProtocol() {
    }

    public static String encode(NetworkMessage message) throws JsonProcessingException {
        return MAPPER.writeValueAsString(message);
    }

    public static NetworkMessage decode(String line) throws JsonProcessingException {
        if (line == null || line.length() > 65_536) {
            throw new JsonProcessingException("Thông điệp vượt quá giới hạn 64 KiB") { };
        }
        return MAPPER.readValue(line, NetworkMessage.class);
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode value(Object value) {
        return MAPPER.valueToTree(value);
    }
}
