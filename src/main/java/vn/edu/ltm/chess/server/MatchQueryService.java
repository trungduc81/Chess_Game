package vn.edu.ltm.chess.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.MessageType;
import vn.edu.ltm.chess.protocol.NetworkMessage;
import vn.edu.ltm.chess.repository.ChessRepository;
import vn.edu.ltm.chess.repository.RankingSort;
import vn.edu.ltm.chess.repository.StoredMove;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import static vn.edu.ltm.chess.server.ServerMessages.*;

/** Đọc lịch sử, bảng xếp hạng và replay; kiểm tra quyền xem trận tại repository. */
final class MatchQueryService {
    private final ChessRepository repository;

    MatchQueryService(ChessRepository repository) {
        this.repository = repository;
    }

    void history(ClientConnection client, NetworkMessage message) throws SQLException {
        client.send(NetworkMessage.reply(MessageType.HISTORY_RESPONSE, message.requestId(),
                JsonProtocol.value(repository.history(client.username()))));
    }

    void ranking(ClientConnection client, NetworkMessage message) throws SQLException {
        client.send(NetworkMessage.reply(MessageType.RANKING_RESPONSE, message.requestId(),
                JsonProtocol.value(repository.ranking(rankingSorts(message.data())))));
    }

    void matchDetail(ClientConnection client, NetworkMessage message) throws SQLException {
        long matchId = message.data().path("matchId").asLong(-1);
        if (matchId < 1) {
            error(client, message, "Mã trận không hợp lệ");
            return;
        }
        boolean admin = "ADMIN".equals(client.role());
        List<StoredMove> moves = admin
                ? repository.adminMatchMoves(matchId)
                : repository.matchMoves(matchId, client.username());
        ObjectNode detail = JsonProtocol.object()
                .put("pgn", admin
                        ? repository.adminMatchPgn(matchId, moves)
                        : repository.matchPgn(matchId, client.username(), moves));
        detail.set("moves", JsonProtocol.value(moves));
        client.send(NetworkMessage.reply(MessageType.MATCH_DETAIL_RESPONSE, message.requestId(), detail));
    }

    private static List<RankingSort> rankingSorts(JsonNode data) {
        List<RankingSort> sorts = new ArrayList<>();
        EnumSet<RankingSort.Field> used = EnumSet.noneOf(RankingSort.Field.class);
        for (JsonNode item : data.path("sorts")) {
            try {
                RankingSort.Field field = RankingSort.Field.valueOf(
                        item.path("field").asText().toUpperCase(Locale.ROOT));
                if (!used.add(field))
                    continue;
                boolean ascending = "ASC".equalsIgnoreCase(item.path("direction").asText());
                sorts.add(new RankingSort(field, ascending));
            } catch (IllegalArgumentException ignored) {
                // Bỏ qua tiêu chí không thuộc danh sách trắng phía server.
            }
        }
        return sorts;
    }
}
