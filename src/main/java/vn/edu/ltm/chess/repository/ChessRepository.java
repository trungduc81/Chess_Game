package vn.edu.ltm.chess.repository;

import vn.edu.ltm.chess.chess.ChessBoard;
import vn.edu.ltm.chess.chess.Move;
import vn.edu.ltm.chess.chess.MoveResult;
import vn.edu.ltm.chess.rating.EloCalculator;
import vn.edu.ltm.chess.security.PasswordHasher;

import java.sql.*;
import java.util.ArrayList;
import java.util.EnumSet;
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
                      role VARCHAR(20) NOT NULL DEFAULT 'PLAYER',
                      account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                      locked_reason VARCHAR(255),
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
                      pgn TEXT NOT NULL,
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
            ensureColumn(connection, "matches", "pgn", "TEXT NULL");
            ensureColumn(connection, "users", "role", "VARCHAR(20) NOT NULL DEFAULT 'PLAYER'");
            ensureColumn(connection, "users", "account_status",
                    "VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'");
            ensureColumn(connection, "users", "locked_reason", "VARCHAR(255) NULL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS admin_audit_logs (
                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                      admin_id BIGINT NOT NULL,
                      action VARCHAR(50) NOT NULL,
                      target_user_id BIGINT,
                      description VARCHAR(255),
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT fk_audit_admin FOREIGN KEY (admin_id) REFERENCES users(id),
                      CONSTRAINT fk_audit_target FOREIGN KEY (target_user_id) REFERENCES users(id),
                      INDEX idx_audit_created (created_at)
                    )""");
        }
    }

    public void bootstrapAdmin(String username, String password) throws SQLException {
        if (username == null || username.isBlank() || password == null || password.isBlank()) return;
        if (!username.matches("[A-Za-z0-9_]{3,40}") || password.length() < 6) {
            throw new SQLException("CHESS_ADMIN_USERNAME hoặc CHESS_ADMIN_PASSWORD không hợp lệ");
        }
        PasswordHasher.Hash hash = PasswordHasher.create(password);
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users(username,password_hash,password_salt,elo,role,account_status,locked_reason)
                     VALUES(?,?,?,?, 'ADMIN','ACTIVE',NULL)
                     ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash),
                       password_salt=VALUES(password_salt),role='ADMIN',account_status='ACTIVE',locked_reason=NULL""")) {
            statement.setString(1, username);
            statement.setString(2, hash.value());
            statement.setString(3, hash.salt());
            statement.setInt(4, EloCalculator.DEFAULT_ELO);
            statement.executeUpdate();
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
                     "SELECT id,username,password_hash,password_salt,elo,role,account_status,locked_reason "
                             + "FROM users WHERE username=?")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !PasswordHasher.verify(password,
                        result.getString("password_hash"), result.getString("password_salt"))) {
                    return Optional.empty();
                }
                return Optional.of(new UserAccount(result.getLong("id"), result.getString("username"),
                        result.getInt("elo"), result.getString("role"),
                        result.getString("account_status"), result.getString("locked_reason")));
            }
        }
    }

    public AdminDashboardStats adminDashboardStats() throws SQLException {
        try (Connection connection = database.connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT (SELECT COUNT(*) FROM users WHERE role='PLAYER') total_users,
                            (SELECT COUNT(*) FROM matches) total_matches""")) {
            result.next();
            return new AdminDashboardStats(result.getInt("total_users"), result.getLong("total_matches"));
        }
    }

    public List<AdminUserRow> adminUsers() throws SQLException {
        String sql = """
                SELECT u.id,u.username,u.elo,u.role,u.account_status,u.locked_reason,u.created_at,
                       COUNT(m.id) total_matches
                FROM users u
                LEFT JOIN matches m ON m.white_player_id=u.id OR m.black_player_id=u.id
                GROUP BY u.id,u.username,u.elo,u.role,u.account_status,u.locked_reason,u.created_at
                ORDER BY u.role DESC,u.username ASC""";
        List<AdminUserRow> rows = new ArrayList<>();
        try (Connection connection = database.connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Timestamp createdAt = result.getTimestamp("created_at");
                rows.add(new AdminUserRow(result.getLong("id"), result.getString("username"),
                        result.getInt("elo"), result.getInt("total_matches"),
                        result.getString("account_status"), result.getString("role"),
                        createdAt == null ? 0 : createdAt.getTime(), result.getString("locked_reason")));
            }
        }
        return rows;
    }

    public List<MatchSummary> adminMatches(List<MatchSort> sorts) throws SQLException {
        String sql = """
                SELECT m.id,w.username white_name,b.username black_name,m.result,m.reason,m.move_count,
                       m.initial_fen,m.white_elo_delta,m.black_elo_delta,
                       UNIX_TIMESTAMP(m.started_at)*1000 started_ms,
                       UNIX_TIMESTAMP(m.ended_at)*1000 ended_ms
                FROM matches m JOIN users w ON w.id=m.white_player_id JOIN users b ON b.id=m.black_player_id
                """ + " ORDER BY " + matchOrder(sorts);
        List<MatchSummary> rows = new ArrayList<>();
        try (Connection connection = database.connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) rows.add(matchSummary(result));
        }
        return rows;
    }

    static String matchOrder(List<MatchSort> requested) {
        List<MatchSort> sorts = requested == null || requested.isEmpty()
                ? List.of(new MatchSort(MatchSort.Field.TIME, false),
                        new MatchSort(MatchSort.Field.MOVE_COUNT, false)) : requested;
        List<String> order = new ArrayList<>();
        EnumSet<MatchSort.Field> used = EnumSet.noneOf(MatchSort.Field.class);
        for (MatchSort sort : sorts) {
            if (sort == null || sort.field() == null || !used.add(sort.field())) continue;
            String column = switch (sort.field()) {
                case TIME -> "m.ended_at";
                case MOVE_COUNT -> "m.move_count";
                case ID -> "m.id";
            };
            order.add(column + (sort.ascending() ? " ASC" : " DESC"));
        }
        if (!used.contains(MatchSort.Field.ID)) order.add("m.id DESC");
        return String.join(", ", order);
    }

    public boolean setAccountStatus(String adminUsername, String targetUsername, String status,
                                    String reason, String action) throws SQLException {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                UserIdentity admin = findIdentity(connection, adminUsername, true);
                UserIdentity target = findIdentity(connection, targetUsername, true);
                if (admin == null || target == null || !"ADMIN".equals(admin.role())
                        || "ADMIN".equals(target.role()) || admin.id() == target.id()) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE users SET account_status=?,locked_reason=? WHERE id=?")) {
                    update.setString(1, status);
                    if (reason == null || reason.isBlank()) update.setNull(2, Types.VARCHAR);
                    else update.setString(2, reason);
                    update.setLong(3, target.id());
                    update.executeUpdate();
                }
                insertAudit(connection, admin.id(), target.id(), action, reason);
                connection.commit();
                return true;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sql) throw sql;
                throw new SQLException(exception);
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public boolean logAdminAction(String adminUsername, String targetUsername,
                                  String action, String description) throws SQLException {
        try (Connection connection = database.connection()) {
            UserIdentity admin = findIdentity(connection, adminUsername, false);
            UserIdentity target = findIdentity(connection, targetUsername, false);
            if (admin == null || target == null || !"ADMIN".equals(admin.role())
                    || "ADMIN".equals(target.role()) || admin.id() == target.id()) return false;
            insertAudit(connection, admin.id(), target.id(), action, description);
            return true;
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
                String pgn = buildPgn(moves, result);

                long matchId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO matches(game_uid,white_player_id,black_player_id,result,reason,
                          white_score,black_score,move_count,initial_fen,pgn,
                          white_elo_before,white_elo_after,white_elo_delta,
                          black_elo_before,black_elo_after,black_elo_delta,started_at,ended_at)
                         VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
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
                    statement.setString(i++, pgn);
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
                       m.initial_fen,m.white_elo_delta,m.black_elo_delta,
                       UNIX_TIMESTAMP(m.started_at)*1000 started_ms,UNIX_TIMESTAMP(m.ended_at)*1000 ended_ms
                FROM matches m JOIN users w ON w.id=m.white_player_id JOIN users b ON b.id=m.black_player_id
                WHERE w.username=? OR b.username=? ORDER BY m.ended_at DESC""";
        List<MatchSummary> rows = new ArrayList<>();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, username);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(matchSummary(result));
            }
        }
        return rows;
    }

    public List<StoredMove> matchMoves(long matchId, String username) throws SQLException {
        return matchMoves(matchId, username, false);
    }

    public List<StoredMove> adminMatchMoves(long matchId) throws SQLException {
        return matchMoves(matchId, null, true);
    }

    private List<StoredMove> matchMoves(long matchId, String username, boolean admin) throws SQLException {
        String sql = admin ? """
                SELECT mv.ply,mv.from_square,mv.to_square,mv.promotion,mv.notation,mv.fen_after,
                       UNIX_TIMESTAMP(mv.played_at)*1000 played_ms,m.initial_fen
                FROM moves mv JOIN matches m ON m.id=mv.match_id
                WHERE mv.match_id=? ORDER BY mv.ply""" : """
                SELECT mv.ply,mv.from_square,mv.to_square,mv.promotion,mv.notation,mv.fen_after,
                       UNIX_TIMESTAMP(mv.played_at)*1000 played_ms,m.initial_fen
                FROM moves mv JOIN matches m ON m.id=mv.match_id
                JOIN users w ON w.id=m.white_player_id JOIN users b ON b.id=m.black_player_id
                WHERE mv.match_id=? AND (w.username=? OR b.username=?) ORDER BY mv.ply""";
        List<StoredMove> moves = new ArrayList<>();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, matchId);
            if (!admin) {
                statement.setString(2, username);
                statement.setString(3, username);
            }
            String initialFen = null;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (initialFen == null) initialFen = result.getString("initial_fen");
                    String promotion = result.getString("promotion");
                    moves.add(new StoredMove(result.getInt("ply"), result.getString("from_square"),
                            result.getString("to_square"), promotion == null ? null : promotion.charAt(0),
                            result.getString("notation"), result.getString("fen_after"), result.getLong("played_ms")));
                }
            }
            if (initialFen != null && moves.stream().anyMatch(move -> move.notation().contains("-"))) {
                moves = normalizeLegacyMoves(initialFen, moves);
                updateMoveNotations(connection, matchId, moves);
            }
        }
        return moves;
    }

    public String matchPgn(long matchId, String username, List<StoredMove> moves) throws SQLException {
        return matchPgn(matchId, username, moves, false);
    }

    public String adminMatchPgn(long matchId, List<StoredMove> moves) throws SQLException {
        return matchPgn(matchId, null, moves, true);
    }

    private String matchPgn(long matchId, String username, List<StoredMove> moves,
                            boolean admin) throws SQLException {
        String sql = admin ? "SELECT COALESCE(pgn,'') pgn,result FROM matches WHERE id=?" : """
                SELECT COALESCE(m.pgn,'') pgn,m.result
                FROM matches m JOIN users w ON w.id=m.white_player_id JOIN users b ON b.id=m.black_player_id
                WHERE m.id=? AND (w.username=? OR b.username=?)""";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, matchId);
            if (!admin) {
                statement.setString(2, username);
                statement.setString(3, username);
            }
            String pgn;
            String matchResult;
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Không tìm thấy ván đấu hoặc bạn không có quyền xem");
                pgn = result.getString("pgn");
                matchResult = result.getString("result");
            }
            if (!pgn.isBlank()) return pgn;
            pgn = buildPgn(moves, matchResult);
            try (PreparedStatement update = connection.prepareStatement("UPDATE matches SET pgn=? WHERE id=?")) {
                update.setString(1, pgn);
                update.setLong(2, matchId);
                update.executeUpdate();
            }
            return pgn;
        }
    }

    public List<RankingRow> ranking(List<RankingSort> requestedSorts) throws SQLException {
        String orderBy = rankingOrder(requestedSorts);
        String sql = """
                WITH player_games AS (
                  SELECT m.id match_id,m.white_player_id player_id,
                         CASE WHEN m.result='WHITE_WIN' THEN 1 ELSE 0 END won
                  FROM matches m
                  UNION ALL
                  SELECT m.id,m.black_player_id,
                         CASE WHEN m.result='BLACK_WIN' THEN 1 ELSE 0 END
                  FROM matches m
                ), aggregated AS (
                  SELECT u.id,u.username,u.elo,
                    COALESCE(SUM(pg.won),0) wins,
                    COUNT(pg.match_id) total_matches
                FROM users u LEFT JOIN player_games pg ON pg.player_id=u.id
                  WHERE u.role='PLAYER'
                  GROUP BY u.id,u.username,u.elo
                ) SELECT username,elo,wins,total_matches,
                         CASE WHEN total_matches=0 THEN 0
                              ELSE wins*100.0/total_matches END win_rate
                  FROM aggregated
                  ORDER BY %s""".formatted(orderBy);
        List<RankingRow> rows = new ArrayList<>();
        try (Connection connection = database.connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            int rank = 1;
            while (result.next()) rows.add(new RankingRow(rank++, result.getString("username"),
                    result.getInt("elo"), result.getInt("wins"),
                    result.getInt("total_matches"), result.getDouble("win_rate")));
        }
        return rows;
    }

    private static String rankingOrder(List<RankingSort> requestedSorts) {
        List<RankingSort> sorts = requestedSorts == null || requestedSorts.isEmpty()
                ? List.of(new RankingSort(RankingSort.Field.ELO, false),
                        new RankingSort(RankingSort.Field.TOTAL_MATCHES, false),
                        new RankingSort(RankingSort.Field.WIN_RATE, false))
                : requestedSorts;
        List<String> order = new ArrayList<>();
        EnumSet<RankingSort.Field> used = EnumSet.noneOf(RankingSort.Field.class);
        for (RankingSort sort : sorts) {
            if (sort == null || !used.add(sort.field()))
                continue;
            String column = switch (sort.field()) {
                case ELO -> "elo";
                case TOTAL_MATCHES -> "total_matches";
                case WIN_RATE -> "win_rate";
            };
            order.add(column + (sort.ascending() ? " ASC" : " DESC"));
        }
        if (order.isEmpty())
            return "elo DESC,total_matches DESC,win_rate DESC,username ASC";
        order.add("username ASC");
        return String.join(",", order);
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

    private static String buildPgn(List<StoredMove> moves, String result) {
        StringBuilder pgn = new StringBuilder();
        for (int index = 0; index < moves.size(); index++) {
            if (index % 2 == 0) pgn.append(index / 2 + 1).append(". ");
            pgn.append(moves.get(index).notation()).append(' ');
        }
        pgn.append(switch (result) {
            case "WHITE_WIN" -> "1-0";
            case "BLACK_WIN" -> "0-1";
            case "DRAW" -> "1/2-1/2";
            default -> "*";
        });
        return pgn.toString().trim();
    }

    private static List<StoredMove> normalizeLegacyMoves(String initialFen, List<StoredMove> moves) {
        ChessBoard board = ChessBoard.fromFen(initialFen);
        List<StoredMove> normalized = new ArrayList<>(moves.size());
        for (StoredMove move : moves) {
            MoveResult result = board.move(new Move(move.from(), move.to(), move.promotion()));
            String notation = result.accepted() ? result.notation() : move.notation();
            normalized.add(new StoredMove(move.ply(), move.from(), move.to(), move.promotion(),
                    notation, move.fenAfter(), move.playedAt()));
        }
        return normalized;
    }

    private static void updateMoveNotations(Connection connection, long matchId,
                                            List<StoredMove> moves) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE moves SET notation=? WHERE match_id=? AND ply=?")) {
            for (StoredMove move : moves) {
                statement.setString(1, move.notation());
                statement.setLong(2, matchId);
                statement.setInt(3, move.ply());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static MatchSummary matchSummary(ResultSet result) throws SQLException {
        return new MatchSummary(result.getLong("id"), result.getString("white_name"),
                result.getString("black_name"), result.getString("result"),
                result.getString("reason"), result.getInt("move_count"),
                result.getString("initial_fen"), result.getInt("white_elo_delta"),
                result.getInt("black_elo_delta"), result.getLong("started_ms"),
                result.getLong("ended_ms"));
    }

    private static UserIdentity findIdentity(Connection connection, String username,
                                             boolean lock) throws SQLException {
        String sql = "SELECT id,role FROM users WHERE username=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new UserIdentity(result.getLong("id"), result.getString("role"));
            }
        }
    }

    private static void insertAudit(Connection connection, long adminId, long targetId,
                                    String action, String description) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO admin_audit_logs(admin_id,action,target_user_id,description)
                VALUES(?,?,?,?)""")) {
            statement.setLong(1, adminId);
            statement.setString(2, action);
            statement.setLong(3, targetId);
            if (description == null || description.isBlank()) statement.setNull(4, Types.VARCHAR);
            else statement.setString(4, description);
            statement.executeUpdate();
        }
    }

    private static void ensureColumn(Connection connection, String table, String column,
                                     String definition) throws SQLException {
        try (ResultSet columns = connection.getMetaData()
                .getColumns(connection.getCatalog(), null, "%", "%")) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static Map<String, PlayerRow> lockPlayers(Connection connection, String white, String black) throws SQLException {
        Map<String, PlayerRow> players = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,username,elo FROM users WHERE username IN (?,?) ORDER BY id ASC FOR UPDATE")) {
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

    private record PlayerRow(long id, int elo) { }
    private record UserIdentity(long id, String role) { }
}
