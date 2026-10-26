package vn.edu.ltm.chess.chess;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ChessBoardTest {
    @Test void acceptsNormalMovesAndRejectsWrongTurn() {
        ChessBoard board = new ChessBoard();
        MoveResult firstMove = board.move(new Move("e2", "e4", null));
        assertTrue(firstMove.accepted());
        assertEquals("e4", firstMove.notation());
        MoveResult wrongTurn = board.move(new Move("d2", "d4", null));
        assertFalse(wrongTurn.accepted());
        assertEquals(ChessColor.BLACK, board.turn());
        assertEquals('P', board.pieceAt("e4"));
    }

    @Test void returnsServerAuthoritativeLegalDestinations() {
        ChessBoard board = new ChessBoard();
        assertEquals(Set.of("e3", "e4"),
                Set.copyOf(board.legalDestinations("e2", ChessColor.WHITE)));
        assertEquals(Set.of("f3", "h3"),
                Set.copyOf(board.legalDestinations("g1", ChessColor.WHITE)));
        assertTrue(board.legalDestinations("e7", ChessColor.WHITE).isEmpty());
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
        assertEquals("Qh4#", mate.notation());
    }

    @Test void supportsCastlingAndEnPassant() {
        ChessBoard castle = new ChessBoard();
        play(castle, "e2", "e4"); play(castle, "e7", "e5");
        play(castle, "g1", "f3"); play(castle, "b8", "c6");
        play(castle, "f1", "e2"); play(castle, "g8", "f6");
        MoveResult castling = castle.move(new Move("e1", "g1", null));
        assertTrue(castling.accepted());
        assertEquals("O-O", castling.notation());
        assertEquals('K', castle.pieceAt("g1"));
        assertEquals('R', castle.pieceAt("f1"));

        ChessBoard passant = new ChessBoard();
        play(passant, "e2", "e4"); play(passant, "a7", "a6");
        play(passant, "e4", "e5"); play(passant, "d7", "d5");
        MoveResult enPassant = passant.move(new Move("e5", "d6", null));
        assertTrue(enPassant.accepted());
        assertEquals("exd6", enPassant.notation());
        assertEquals('P', passant.pieceAt("d6"));
        assertEquals('.', passant.pieceAt("d5"));
    }

    @Test void supportsPromotionAndFenLoading() {
        ChessBoard board = ChessBoard.fromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1");
        MoveResult result = board.move(new Move("a7", "a8", 'n'));
        assertTrue(result.accepted());
        assertEquals("a8=N", result.notation());
        assertEquals('N', board.pieceAt("a8"));
        assertEquals(ChessColor.BLACK, board.turn());
    }

    @Test void promotedRookMovesHorizontallyAndCapturesNormally() {
        ChessBoard board = ChessBoard.fromFen("4k3/8/8/8/8/8/2p5/6NK b - - 0 1");
        assertTrue(board.move(new Move("c2", "c1", 'r')).accepted());
        assertEquals('r', board.pieceAt("c1"));

        assertTrue(board.move(new Move("h1", "h2", null)).accepted());
        MoveResult rookCapture = board.move(new Move("c1", "g1", null));
        assertTrue(rookCapture.accepted());
        assertEquals('r', board.pieceAt("g1"));
        assertEquals('.', board.pieceAt("c1"));
    }

    @Test void rejectsPromotedRookMoveWhenBlackMustAnswerCheck() {
        ChessBoard board = ChessBoard.fromFen(
                "rnbqkQ2/3ppp2/1p6/p7/8/4P2P/2rP2P1/1NBQKBNR b - - 0 1");
        assertTrue(board.isKingInCheck(ChessColor.BLACK));
        assertTrue(board.legalDestinations("c2", ChessColor.BLACK).isEmpty());

        MoveResult ignoredCheck = board.move(new Move("c2", "c1", null));
        assertFalse(ignoredCheck.accepted());
        assertEquals("Nước đi không hợp lệ", ignoredCheck.message());
        assertEquals('r', board.pieceAt("c2"));
    }

    @Test void createsStandardSanForPiecesCapturesAndDisambiguation() {
        ChessBoard normal = new ChessBoard();
        play(normal, "e2", "e4");
        play(normal, "e7", "e5");
        MoveResult knight = normal.move(new Move("g1", "f3", null));
        assertEquals("Nf3", knight.notation());

        ChessBoard capture = new ChessBoard();
        play(capture, "e2", "e4");
        play(capture, "d7", "d5");
        MoveResult pawnCapture = capture.move(new Move("e4", "d5", null));
        assertEquals("exd5", pawnCapture.notation());

        ChessBoard pieceCapture = ChessBoard.fromFen("r3k3/5p2/8/8/2B5/8/8/R3K3 w - - 0 1");
        MoveResult bishopCapture = pieceCapture.move(new Move("c4", "f7", null));
        assertEquals("Bxf7+", bishopCapture.notation());

        ChessBoard ambiguous = ChessBoard.fromFen("7k/8/8/8/8/8/3N3N/K7 w - - 0 1");
        MoveResult distinguishedKnight = ambiguous.move(new Move("d2", "f3", null));
        assertTrue(distinguishedKnight.accepted());
        assertEquals("Ndf3", distinguishedKnight.notation());

        ChessBoard queenSideCastle = ChessBoard.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        assertEquals("O-O-O", queenSideCastle.move(new Move("e1", "c1", null)).notation());

        ChessBoard checkingPromotion = ChessBoard.fromFen("7k/4P3/8/8/8/8/8/K7 w - - 0 1");
        assertEquals("e8=Q+", checkingPromotion.move(new Move("e7", "e8", 'q')).notation());
    }

    private static void play(ChessBoard board, String from, String to) {
        assertTrue(board.move(new Move(from, to, null)).accepted(), from + "-" + to);
    }
}
