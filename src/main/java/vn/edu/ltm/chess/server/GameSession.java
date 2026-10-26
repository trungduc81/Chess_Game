package vn.edu.ltm.chess.server;

import vn.edu.ltm.chess.chess.*;
import vn.edu.ltm.chess.repository.StoredMove;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Trạng thái ván đấu chính thức nằm hoàn toàn ở server. */
public final class GameSession {
    public enum Phase { PLAYING, FINISHING, AWAITING_REMATCH, CLOSED }
    public record TurnResult(boolean accepted, String error, MoveResult moveResult,
                             boolean finished, String timedOutPlayer) { }
    public record StateSnapshot(String fen, ChessColor turn, boolean inCheck,
                                long deadlineEpochMs, List<StoredMove> moves) { }

    private final String id = UUID.randomUUID().toString();
    private final String white;
    private final String black;
    private final long turnTimeoutMillis;
    private final ChessBoard board = new ChessBoard();
    private final List<StoredMove> moves = new ArrayList<>();
    private final long startedAt = System.currentTimeMillis();
    private long deadlineEpochMs;
    private Phase phase = Phase.PLAYING;
    private String drawOfferedBy;
    private Boolean whiteRematch;
    private Boolean blackRematch;
    private boolean persisted;

    public GameSession(String white, String black) {
        this(white, black, 30_000L);
    }

    public GameSession(String white, String black, long turnTimeoutMillis) {
        this.white = white;
        this.black = black;
        this.turnTimeoutMillis = turnTimeoutMillis;
        this.deadlineEpochMs = startedAt + turnTimeoutMillis;
    }

    public synchronized TurnResult move(String player, Move move) {
        if (phase != Phase.PLAYING) {
            return new TurnResult(false, "Ván đấu không còn nhận nước đi", null, false, null);
        }
        ChessColor color = colorOf(player);
        if (color == null) return new TurnResult(false, "Bạn không thuộc ván đấu này", null, false, null);

        long acceptedAt = System.currentTimeMillis();
        if (acceptedAt > deadlineEpochMs) {
            String loser = playerFor(board.turn());
            phase = Phase.FINISHING;
            return new TurnResult(false, "Đã hết thời gian đi", null, false, loser);
        }
        if (color != board.turn()) {
            return new TurnResult(false, "Chưa đến lượt của bạn", null, false, null);
        }

        MoveResult result = board.move(move);
        if (!result.accepted()) return new TurnResult(false, result.message(), result, false, null);
        moves.add(new StoredMove(moves.size() + 1, move.from(), move.to(), move.promotion(),
                result.notation(), result.fen(), acceptedAt));
        drawOfferedBy = null;
        boolean finished = result.outcome() != GameOutcome.PLAYING;
        if (finished) phase = Phase.FINISHING;
        else deadlineEpochMs = acceptedAt + turnTimeoutMillis;
        return new TurnResult(true, null, result, finished, null);
    }

    public synchronized List<String> legalDestinations(String player, String from) {
        ChessColor color = colorOf(player);
        if (phase != Phase.PLAYING || color == null || color != board.turn()) return List.of();
        return board.legalDestinations(from, color);
    }

    /** Chốt timeout trong cùng monitor với MOVE để tránh hai kết quả cho một lượt. */
    public synchronized String claimTimeout(long now) {
        if (phase != Phase.PLAYING || now <= deadlineEpochMs) return null;
        String loser = playerFor(board.turn());
        phase = Phase.FINISHING;
        return loser;
    }

    public synchronized boolean claimFinish() {
        if (phase != Phase.PLAYING) return false;
        phase = Phase.FINISHING;
        return true;
    }

    public synchronized boolean offerDraw(String player) {
        if (phase != Phase.PLAYING || colorOf(player) == null || drawOfferedBy != null) return false;
        drawOfferedBy = player;
        return true;
    }

    public synchronized boolean canAnswerDraw(String player) {
        return phase == Phase.PLAYING && drawOfferedBy != null
                && !drawOfferedBy.equals(player) && colorOf(player) != null;
    }

    public synchronized void clearDrawOffer() { drawOfferedBy = null; }

    public synchronized void finish() {
        if (phase != Phase.CLOSED) phase = Phase.AWAITING_REMATCH;
        drawOfferedBy = null;
    }

    public synchronized void close() { phase = Phase.CLOSED; }

    public synchronized boolean voteRematch(String player, boolean accept) {
        if (phase != Phase.AWAITING_REMATCH) return false;
        if (player.equals(white)) whiteRematch = accept;
        else if (player.equals(black)) blackRematch = accept;
        else return false;
        return true;
    }

    public synchronized Boolean rematchDecision() {
        if (Boolean.FALSE.equals(whiteRematch) || Boolean.FALSE.equals(blackRematch)) return false;
        if (Boolean.TRUE.equals(whiteRematch) && Boolean.TRUE.equals(blackRematch)) return true;
        return null;
    }

    public ChessColor colorOf(String player) {
        if (white.equals(player)) return ChessColor.WHITE;
        if (black.equals(player)) return ChessColor.BLACK;
        return null;
    }

    private String playerFor(ChessColor color) { return color == ChessColor.WHITE ? white : black; }
    public String opponentOf(String player) { return white.equals(player) ? black : white; }
    public String id() { return id; }
    public String white() { return white; }
    public String black() { return black; }
    public synchronized String fen() { return board.toFen(); }
    public synchronized ChessColor turn() { return board.turn(); }
    public synchronized boolean currentTurnInCheck() { return board.isKingInCheck(board.turn()); }
    public synchronized StateSnapshot stateSnapshot() {
        ChessColor currentTurn = board.turn();
        return new StateSnapshot(board.toFen(), currentTurn, board.isKingInCheck(currentTurn),
                deadlineEpochMs, List.copyOf(moves));
    }
    public synchronized long deadlineEpochMs() { return deadlineEpochMs; }
    public synchronized Phase phase() { return phase; }
    public long startedAt() { return startedAt; }
    public synchronized List<StoredMove> moves() { return List.copyOf(moves); }

    public synchronized boolean beginPersistence() {
        if (persisted) return false;
        persisted = true;
        return true;
    }
}
