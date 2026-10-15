package vn.edu.ltm.chess.chess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChessBoardTest {
    @Test void acceptsNormalMovesAndRejectsWrongTurn() {
        ChessBoard board = new ChessBoard();
        assertTrue(board.move(new Move("e2", "e4", null)).accepted());
        MoveResult wrongTurn = board.move(new Move("d2", "d4", null));
        assertFalse(wrongTurn.accepted());
        assertEquals(ChessColor.BLACK, board.turn());
        assertEquals('P', board.pieceAt("e4"));
    }

    @Test void rejectsMoveThatLeavesKingInCheck() {
        ChessBoard board = new ChessBoard();
        play(board, "f2", "f3");
        play(board, "e7", "e5");
        play(board, "g2", "g4");
        MoveResult mate = board.move(new Move("d8", "h4", null));
        assertTrue(mate.accepted());
        assertEquals(GameOutcome.BLACK_WIN, mate.outcome());
        assertEquals("Chiếu bí", mate.reason());
    }

    @Test void supportsCastlingAndEnPassant() {
        ChessBoard castle = new ChessBoard();
        play(castle, "e2", "e4"); play(castle, "e7", "e5");
        play(castle, "g1", "f3"); play(castle, "b8", "c6");
        play(castle, "f1", "e2"); play(castle, "g8", "f6");
        assertTrue(castle.move(new Move("e1", "g1", null)).accepted());
        assertEquals('K', castle.pieceAt("g1"));
        assertEquals('R', castle.pieceAt("f1"));

        ChessBoard passant = new ChessBoard();
        play(passant, "e2", "e4"); play(passant, "a7", "a6");
        play(passant, "e4", "e5"); play(passant, "d7", "d5");
        assertTrue(passant.move(new Move("e5", "d6", null)).accepted());
        assertEquals('P', passant.pieceAt("d6"));
        assertEquals('.', passant.pieceAt("d5"));
    }

    @Test void supportsPromotionAndFenLoading() {
        ChessBoard board = ChessBoard.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        MoveResult result = board.move(new Move("a7", "a8", 'n'));
        assertTrue(result.accepted());
        assertEquals('N', board.pieceAt("a8"));
        assertEquals(ChessColor.BLACK, board.turn());
    }

    private static void play(ChessBoard board, String from, String to) {
        assertTrue(board.move(new Move(from, to, null)).accepted(), from + "-" + to);
    }
}
