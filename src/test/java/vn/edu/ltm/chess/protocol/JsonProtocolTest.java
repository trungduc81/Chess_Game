package vn.edu.ltm.chess.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonProtocolTest {
    @Test void preservesCommonMessageFrame() throws Exception {
        NetworkMessage source = new NetworkMessage(MessageType.MOVE, "request-1", "token-1", "game-1",
                JsonProtocol.object().put("from", "e2").put("to", "e4"));
        NetworkMessage decoded = JsonProtocol.decode(JsonProtocol.encode(source));
        assertEquals(MessageType.MOVE, decoded.type());
        assertEquals("request-1", decoded.requestId());
        assertEquals("token-1", decoded.sessionToken());
        assertEquals("game-1", decoded.gameId());
        assertEquals("e2", decoded.payload().path("from").asText());
    }
}
