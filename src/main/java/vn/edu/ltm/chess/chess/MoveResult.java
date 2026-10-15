package vn.edu.ltm.chess.chess;

public record MoveResult(
        boolean accepted,
        String message,
        String notation,
        String fen,
        ChessColor nextTurn,
        GameOutcome outcome,
        String reason) {

    public static MoveResult rejected(String message, String fen, ChessColor turn) {
        return new MoveResult(false, message, null, fen, turn, GameOutcome.PLAYING, null);
    }
}
