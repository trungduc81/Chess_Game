package vn.edu.ltm.chess.repository;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ChessRepository {
    private final Database database;

    public ChessRepository(Database database) {
        this.database = database;
    }

    public void initialize() throws SQLException {
        try (Connection connection = database.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      username VARCHAR(40) NOT NULL UNIQUE,
                      password_hash VARCHAR(128) NOT NULL,
                      password_salt VARCHAR(64) NOT NULL,
                      elo INT NOT NULL DEFAULT 1200,
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS matches (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      game_uid VARCHAR(36) NOT NULL UNIQUE,
                      white_player_id BIGINT NOT NULL,
                      black_player_id BIGINT NOT NULL,
                      result VARCHAR(20) NOT NULL,
                      reason VARCHAR(120) NOT NULL,
                      white_score DECIMAL(2,1) NOT NULL,
                      black_score DECIMAL(2,1) NOT NULL,
                      move_count INT NOT NULL,
                      initial_fen VARCHAR(120) NOT NULL,
                      white_elo_before INT NOT NULL,
                      white_elo_after INT NOT NULL,
                      white_elo_delta INT NOT NULL,
                      black_elo_before INT NOT NULL,
                      black_elo_after INT NOT NULL,
                      black_elo_delta INT NOT NULL,
                      started_at TIMESTAMP(3) NOT NULL,
                      ended_at TIMESTAMP(3) NOT NULL,
                      CONSTRAINT fk_match_white FOREIGN KEY (white_player_id) REFERENCES users(id),
                      CONSTRAINT fk_match_black FOREIGN KEY (black_player_id) REFERENCES users(id),
                      INDEX idx_match_white_ended (white_player_id, ended_at),
                      INDEX idx_match_black_ended (black_player_id, ended_at)
                    )""");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS moves (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      match_id BIGINT NOT NULL,
                      ply INT NOT NULL,
                      from_square CHAR(2) NOT NULL,
                      to_square CHAR(2) NOT NULL,
                      promotion CHAR(1),
                      notation VARCHAR(30) NOT NULL,
                      fen_after VARCHAR(120) NOT NULL,
                      played_at TIMESTAMP(3) NOT NULL,
                      CONSTRAINT fk_move_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
                      UNIQUE KEY uq_match_ply (match_id, ply)
                    )""");
        }
    }

    public boolean register(String username, String password) throws SQLException {
        PasswordHasher.Hash hash = PasswordHasher.create(password);
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO users(username,password_hash,password_salt,elo) VALUES(?,?,?,?)")) {
            statement.setString(1, username);
            statement.setString(2, hash.value());
            statement.setString(3, hash.salt());
            statement.setInt(4, EloCalculator.DEFAULT_ELO);
            statement.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return false;
        }
    }

    public Optional<UserAccount> authenticate(String username, String password) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id,username,password_hash,password_salt,elo FROM users WHERE username=?")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !PasswordHasher.verify(password,
                        result.getString("password_hash"), result.getString("password_salt"))) {
                    return Optional.empty();
                }
                return Optional.of(new UserAccount(result.getLong("id"), result.getString("username"),
                        result.getInt("elo")));
            }
        }
    }

    public int elo(String username) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("SELECT elo FROM users WHERE username=?")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Không tìm thấy người chơi " + username);
                return result.getInt(1);
            }
        }
    }

    public SavedMatch saveMatch(String gameUid, String white, String black, String result, String reason,
                                long startedAt, long endedAt, List<StoredMove> moves) throws SQLException {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                SavedMatch existing = findSavedMatch(connection, gameUid);
                if (existing != null) {
                    connection.rollback();
                    return existing;
                }

                Map<String, PlayerRow> players = lockPlayers(connection, white, black);
                PlayerRow whiteRow = requirePlayer(players, white);
                PlayerRow blackRow = requirePlayer(players, black);
                double whiteScore = switch (result) {
                    case "WHITE_WIN" -> 1.0;
                    case "BLACK_WIN" -> 0.0;
                    default -> 0.5;
                };
                double blackScore = 1.0 - whiteScore;
                int whiteDelta = EloCalculator.whiteDelta(whiteRow.elo, blackRow.elo, whiteScore);
                int blackDelta = -whiteDelta;
                int whiteAfter = whiteRow.elo + whiteDelta;
                int blackAfter = blackRow.elo + blackDelta;

                long matchId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO matches(game_uid,white_player_id,black_player_id,result,reason,
                          white_score,black_score,move_count,initial_fen,
                          white_elo_before,white_elo_after,white_elo_delta,
                          black_elo_before,black_elo_after,black_elo_delta,started_at,ended_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
                    int i = 1;
                    statement.setString(i++, gameUid);
                    statement.setLong(i++, whiteRow.id);
                    statement.setLong(i++, blackRow.id);
                    statement.setString(i++, result);
                    statement.setString(i++, reason);
                    statement.setDouble(i++, whiteScore);
                    statement.setDouble(i++, blackScore);
                    statement.setInt(i++, moves.size());
                    statement.setString(i++, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
                    statement.setInt(i++, whiteRow.elo);
                    statement.setInt(i++, whiteAfter);
                    statement.setInt(i++, whiteDelta);
                    statement.setInt(i++, blackRow.elo);
                    statement.setInt(i++, blackAfter);
                    statement.setInt(i++, blackDelta);
                    statement.setTimestamp(i++, new Timestamp(startedAt));
                    statement.setTimestamp(i, new Timestamp(endedAt));
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Không nhận được mã trận đấu");
                        matchId = keys.getLong(1);
                    }
                }

                insertMoves(connection, matchId, moves);
                updateElo(connection, whiteRow.id, whiteAfter);
                updateElo(connection, blackRow.id, blackAfter);
                connection.commit();
                return new SavedMatch(matchId, whiteRow.elo, whiteAfter, whiteDelta,
                        blackRow.elo, blackAfter, blackDelta, true);
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sql) throw sql;
                throw new SQLException(exception);
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<MatchSummary> history(String username) throws SQLException {
        String sql = """
                SELECT m.id,w.username white_name,b.username black_name,m.result,m.reason,m.move_count,
                       m.white_elo_delta,m.black_elo_delta,
                       UNIX_TIMESTAMP(m.started_at)*1000 started_ms,UNIX_TIMESTAMP(m.ended_at)*1000 ended_ms
                FROM matches m JOIN users w ON w.id=m.white_player_id JOIN users b ON b.id=m.black_player_id
                WHERE w.username=? OR b.username=? ORDER BY m.ended_at DESC""";
        List<MatchSummary> rows = new ArrayList<>();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, username);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(new MatchSummary(result.getLong("id"), result.getString("white_name"),
                        result.getString("black_name"), result.getString("result"), result.getString("reason"),
                        result.getInt("move_count"), result.getInt("white_elo_delta"),
                        result.getInt("black_elo_delta"), result.getLong("started_ms"), result.getLong("ended_ms")));
            }
        }
        return rows;
    }

    public List<StoredMove> matchMoves(long matchId, String username) throws SQLException {
        String sql = """
                SELECT mv.ply,mv.from_square,mv.to_square,mv.promotion,mv.notation,mv.fen_after,
                       UNIX_TIMESTAMP(mv.played_at)*1000 played_ms
                FROM moves mv JOIN matches m ON m.id=mv.match_id
                JOIN users w ON w.id=m.white_player_id JOIN users b ON b.id=m.black_player_id
                WHERE mv.match_id=? AND (w.username=? OR b.username=?) ORDER BY mv.ply""";
        List<StoredMove> moves = new ArrayList<>();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, matchId);
            statement.setString(2, username);
            statement.setString(3, username);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String promotion = result.getString("promotion");
                    moves.add(new StoredMove(result.getInt("ply"), result.getString("from_square"),
                            result.getString("to_square"), promotion == null ? null : promotion.charAt(0),
                            result.getString("notation"), result.getString("fen_after"), result.getLong("played_ms")));
                }
            }
        }
        return moves;
    }

    public List<RankingRow> ranking() throws SQLException {
        String sql = """
                WITH player_games AS (
                  SELECT m.id match_id,m.white_player_id player_id,m.black_player_id opponent_id,m.result,m.move_count FROM matches m
                  UNION ALL
                  SELECT m.id,m.black_player_id,m.white_player_id,m.result,m.move_count FROM matches m
                ), aggregated AS (
                  SELECT u.id,u.username,u.elo,
                    AVG(o.elo) avg_opponent_elo,
                    AVG(CASE WHEN (pg.player_id=m.white_player_id AND pg.result='WHITE_WIN') OR
                                      (pg.player_id=m.black_player_id AND pg.result='BLACK_WIN') THEN pg.move_count END) avg_win_plies,
                    AVG(CASE WHEN (pg.player_id=m.white_player_id AND pg.result='BLACK_WIN') OR
                                      (pg.player_id=m.black_player_id AND pg.result='WHITE_WIN') THEN pg.move_count END) avg_loss_plies
                  FROM users u LEFT JOIN player_games pg ON pg.player_id=u.id
                  LEFT JOIN users o ON o.id=pg.opponent_id
                  LEFT JOIN matches m ON m.id=pg.match_id
                  GROUP BY u.id,u.username,u.elo
                ) SELECT username,elo,avg_opponent_elo,avg_win_plies,avg_loss_plies FROM aggregated
                  ORDER BY elo DESC,avg_opponent_elo DESC,
                           COALESCE(avg_win_plies,999999) ASC,COALESCE(avg_loss_plies,0) DESC,username ASC""";
        List<RankingRow> rows = new ArrayList<>();
        try (Connection connection = database.connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            int rank = 1;
            while (result.next()) rows.add(new RankingRow(rank++, result.getString("username"),
                    result.getInt("elo"), nullableDouble(result, "avg_opponent_elo"),
                    nullableDouble(result, "avg_win_plies"), nullableDouble(result, "avg_loss_plies")));
        }
        return rows;
    }

    private static void insertMoves(Connection connection, long matchId, List<StoredMove> moves) throws SQLException {
        if (moves.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO moves(match_id,ply,from_square,to_square,promotion,notation,fen_after,played_at)
                VALUES(?,?,?,?,?,?,?,?)""")) {
            for (StoredMove move : moves) {
                statement.setLong(1, matchId);
                statement.setInt(2, move.ply());
                statement.setString(3, move.from());
                statement.setString(4, move.to());
                if (move.promotion() == null) statement.setNull(5, Types.CHAR);
                else statement.setString(5, String.valueOf(move.promotion()));
                statement.setString(6, move.notation());
                statement.setString(7, move.fenAfter());
                statement.setTimestamp(8, new Timestamp(move.playedAt()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Map<String, PlayerRow> lockPlayers(Connection connection, String white, String black) throws SQLException {
        Map<String, PlayerRow> players = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,username,elo FROM users WHERE username IN (?,?) ORDER BY id FOR UPDATE")) {
            statement.setString(1, white);
            statement.setString(2, black);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) players.put(result.getString("username"),
                        new PlayerRow(result.getLong("id"), result.getInt("elo")));
            }
        }
        return players;
    }

    private static PlayerRow requirePlayer(Map<String, PlayerRow> players, String username) throws SQLException {
        PlayerRow row = players.get(username);
        if (row == null) throw new SQLException("Không tìm thấy người chơi " + username);
        return row;
    }

    private static void updateElo(Connection connection, long userId, int elo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE users SET elo=? WHERE id=?")) {
            statement.setInt(1, elo);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }

    private static SavedMatch findSavedMatch(Connection connection, String gameUid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id,white_elo_before,white_elo_after,white_elo_delta,
                       black_elo_before,black_elo_after,black_elo_delta
                FROM matches WHERE game_uid=?""")) {
            statement.setString(1, gameUid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new SavedMatch(result.getLong("id"), result.getInt("white_elo_before"),
                        result.getInt("white_elo_after"), result.getInt("white_elo_delta"),
                        result.getInt("black_elo_before"), result.getInt("black_elo_after"),
                        result.getInt("black_elo_delta"), false);
            }
        }
    }

    private static Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private record PlayerRow(long id, int elo) { }
}
