package vn.edu.ltm.chess.chess;

import java.util.HashMap;
import java.util.Map;

/**
 * Server-authoritative chess board. Uppercase pieces are white, lowercase are black.
 * The implementation validates normal moves, castling, en-passant, promotion and king safety.
 */
public final class ChessBoard {
    private final char[][] board = new char[8][8];
    private ChessColor turn = ChessColor.WHITE;
    private boolean whiteKingSide = true;
    private boolean whiteQueenSide = true;
    private boolean blackKingSide = true;
    private boolean blackQueenSide = true;
    private int enPassantRow = -1;
    private int enPassantCol = -1;
    private int halfMoveClock;
    private int fullMoveNumber = 1;
    private final Map<String, Integer> repetitions = new HashMap<>();

    public ChessBoard() {
        reset();
    }

    public static ChessBoard fromFen(String fen) {
        ChessBoard result = new ChessBoard();
        result.loadFen(fen);
        return result;
    }

    private ChessBoard(ChessBoard other) {
        for (int row = 0; row < 8; row++) {
            System.arraycopy(other.board[row], 0, board[row], 0, 8);
        }
        turn = other.turn;
        whiteKingSide = other.whiteKingSide;
        whiteQueenSide = other.whiteQueenSide;
        blackKingSide = other.blackKingSide;
        blackQueenSide = other.blackQueenSide;
        enPassantRow = other.enPassantRow;
        enPassantCol = other.enPassantCol;
        halfMoveClock = other.halfMoveClock;
        fullMoveNumber = other.fullMoveNumber;
        repetitions.putAll(other.repetitions);
    }

    public void reset() {
        String[] rows = {
                "rnbqkbnr",
                "pppppppp",
                "........",
                "........",
                "........",
                "........",
                "PPPPPPPP",
                "RNBQKBNR"
        };
        for (int row = 0; row < 8; row++) {
            board[row] = rows[row].toCharArray();
        }
        turn = ChessColor.WHITE;
        whiteKingSide = whiteQueenSide = blackKingSide = blackQueenSide = true;
        enPassantRow = enPassantCol = -1;
        halfMoveClock = 0;
        fullMoveNumber = 1;
        repetitions.clear();
        repetitions.put(positionKey(), 1);
    }

    private synchronized void loadFen(String fen) {
        if (fen == null) throw new IllegalArgumentException("FEN không được để trống");
        String[] fields = fen.trim().split("\\s+");
        if (fields.length != 6) throw new IllegalArgumentException("FEN phải có 6 trường");
        String[] rows = fields[0].split("/");
        if (rows.length != 8) throw new IllegalArgumentException("FEN phải có 8 hàng");
        for (int row = 0; row < 8; row++) {
            int column = 0;
            for (char token : rows[row].toCharArray()) {
                if (Character.isDigit(token)) {
                    int empty = Character.digit(token, 10);
                    if (empty < 1 || empty > 8 || column + empty > 8) throw new IllegalArgumentException("FEN không hợp lệ");
                    for (int i = 0; i < empty; i++) board[row][column++] = '.';
                } else {
                    if ("prnbqkPRNBQK".indexOf(token) < 0 || column >= 8) throw new IllegalArgumentException("FEN không hợp lệ");
                    board[row][column++] = token;
                }
            }
            if (column != 8) throw new IllegalArgumentException("FEN không hợp lệ");
        }
        turn = switch (fields[1]) {
            case "w" -> ChessColor.WHITE;
            case "b" -> ChessColor.BLACK;
            default -> throw new IllegalArgumentException("Lượt trong FEN không hợp lệ");
        };
        String castling = fields[2];
        whiteKingSide = castling.contains("K");
        whiteQueenSide = castling.contains("Q");
        blackKingSide = castling.contains("k");
        blackQueenSide = castling.contains("q");
        if ("-".equals(fields[3])) {
            enPassantRow = enPassantCol = -1;
        } else if (validSquare(fields[3])) {
            enPassantRow = row(fields[3]);
            enPassantCol = col(fields[3]);
        } else {
            throw new IllegalArgumentException("Ô bắt tốt qua đường trong FEN không hợp lệ");
        }
        try {
            halfMoveClock = Integer.parseInt(fields[4]);
            fullMoveNumber = Integer.parseInt(fields[5]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Bộ đếm trong FEN không hợp lệ", exception);
        }
        repetitions.clear();
        repetitions.put(positionKey(), 1);
    }

    public synchronized MoveResult move(Move move) {
        if (!validSquare(move.from()) || !validSquare(move.to())) {
            return MoveResult.rejected("Tọa độ ô cờ không hợp lệ", toFen(), turn);
        }
        int fromRow = row(move.from());
        int fromCol = col(move.from());
        int toRow = row(move.to());
        int toCol = col(move.to());
        char piece = board[fromRow][fromCol];
        if (piece == '.') {
            return MoveResult.rejected("Ô xuất phát không có quân cờ", toFen(), turn);
        }
        if (colorOf(piece) != turn) {
            return MoveResult.rejected("Bạn không được đi quân của đối thủ", toFen(), turn);
        }
        if (!isLegal(move, turn)) {
            return MoveResult.rejected("Nước đi không hợp lệ", toFen(), turn);
        }

        ChessColor mover = turn;
        String notation = move.from() + "-" + move.to()
                + (move.promotion() == null ? "" : "=" + Character.toUpperCase(move.promotion()));
        applyUnchecked(move);

        GameOutcome outcome = GameOutcome.PLAYING;
        String reason = null;
        if (!hasAnyLegalMove(turn)) {
            if (isKingInCheck(turn)) {
                outcome = mover == ChessColor.WHITE ? GameOutcome.WHITE_WIN : GameOutcome.BLACK_WIN;
                reason = "Chiếu bí";
                notation += "#";
            } else {
                outcome = GameOutcome.DRAW;
                reason = "Bí nước";
            }
        } else if (halfMoveClock >= 100) {
            outcome = GameOutcome.DRAW;
            reason = "Hòa theo luật 50 nước";
        } else if (repetitions.getOrDefault(positionKey(), 0) >= 3) {
            outcome = GameOutcome.DRAW;
            reason = "Lặp lại thế cờ 3 lần";
        } else if (insufficientMaterial()) {
            outcome = GameOutcome.DRAW;
            reason = "Không đủ quân chiếu bí";
        } else if (isKingInCheck(turn)) {
            notation += "+";
        }

        return new MoveResult(true, "Nước đi hợp lệ", notation, toFen(), turn, outcome, reason);
    }

    public synchronized boolean isLegal(Move move, ChessColor color) {
        if (!validSquare(move.from()) || !validSquare(move.to())) {
            return false;
        }
        int fr = row(move.from());
        int fc = col(move.from());
        int tr = row(move.to());
        int tc = col(move.to());
        char piece = board[fr][fc];
        if (piece == '.' || colorOf(piece) != color || (board[tr][tc] != '.' && colorOf(board[tr][tc]) == color)) {
            return false;
        }
        if (!pseudoLegal(move, color)) {
            return false;
        }
        ChessBoard copy = new ChessBoard(this);
        copy.applyUnchecked(move);
        return !copy.isKingInCheck(color);
    }

    private boolean pseudoLegal(Move move, ChessColor color) {
        int fr = row(move.from());
        int fc = col(move.from());
        int tr = row(move.to());
        int tc = col(move.to());
        int dr = tr - fr;
        int dc = tc - fc;
        char piece = Character.toLowerCase(board[fr][fc]);

        return switch (piece) {
            case 'p' -> pawnMoveLegal(move, color, fr, fc, tr, tc, dr, dc);
            case 'n' -> (Math.abs(dr) == 2 && Math.abs(dc) == 1)
                    || (Math.abs(dr) == 1 && Math.abs(dc) == 2);
            case 'b' -> Math.abs(dr) == Math.abs(dc) && pathClear(fr, fc, tr, tc);
            case 'r' -> (dr == 0 || dc == 0) && pathClear(fr, fc, tr, tc);
            case 'q' -> (dr == 0 || dc == 0 || Math.abs(dr) == Math.abs(dc))
                    && pathClear(fr, fc, tr, tc);
            case 'k' -> kingMoveLegal(color, fr, fc, tr, tc, dr, dc);
            default -> false;
        };
    }

    private boolean pawnMoveLegal(Move move, ChessColor color, int fr, int fc, int tr, int tc, int dr, int dc) {
        int direction = color == ChessColor.WHITE ? -1 : 1;
        int startRow = color == ChessColor.WHITE ? 6 : 1;
        int promotionRow = color == ChessColor.WHITE ? 0 : 7;
        boolean legal;

        if (dc == 0) {
            legal = dr == direction && board[tr][tc] == '.';
            if (!legal && fr == startRow && dr == 2 * direction && board[tr][tc] == '.') {
                legal = board[fr + direction][fc] == '.';
            }
        } else if (Math.abs(dc) == 1 && dr == direction) {
            legal = board[tr][tc] != '.' && colorOf(board[tr][tc]) != color;
            if (!legal && tr == enPassantRow && tc == enPassantCol && board[tr][tc] == '.') {
                char captured = board[fr][tc];
                legal = Character.toLowerCase(captured) == 'p' && colorOf(captured) != color;
            }
        } else {
            legal = false;
        }

        if (!legal) {
            return false;
        }
        if (tr == promotionRow) {
            char promotion = move.promotion() == null ? 'q' : Character.toLowerCase(move.promotion());
            return promotion == 'q' || promotion == 'r' || promotion == 'b' || promotion == 'n';
        }
        return move.promotion() == null;
    }

    private boolean kingMoveLegal(ChessColor color, int fr, int fc, int tr, int tc, int dr, int dc) {
        if (Math.max(Math.abs(dr), Math.abs(dc)) == 1) {
            return true;
        }
        if (dr != 0 || Math.abs(dc) != 2 || fc != 4) {
            return false;
        }
        int homeRow = color == ChessColor.WHITE ? 7 : 0;
        if (fr != homeRow || isKingInCheck(color)) {
            return false;
        }
        if (dc == 2) {
            boolean right = color == ChessColor.WHITE ? whiteKingSide : blackKingSide;
            char rook = color == ChessColor.WHITE ? 'R' : 'r';
            return right && board[homeRow][5] == '.' && board[homeRow][6] == '.'
                    && board[homeRow][7] == rook
                    && !isSquareAttacked(homeRow, 5, color.opposite())
                    && !isSquareAttacked(homeRow, 6, color.opposite());
        }
        boolean right = color == ChessColor.WHITE ? whiteQueenSide : blackQueenSide;
        char rook = color == ChessColor.WHITE ? 'R' : 'r';
        return right && board[homeRow][1] == '.' && board[homeRow][2] == '.' && board[homeRow][3] == '.'
                && board[homeRow][0] == rook
                && !isSquareAttacked(homeRow, 3, color.opposite())
                && !isSquareAttacked(homeRow, 2, color.opposite());
    }

    private void applyUnchecked(Move move) {
        int fr = row(move.from());
        int fc = col(move.from());
        int tr = row(move.to());
        int tc = col(move.to());
        char piece = board[fr][fc];
        char captured = board[tr][tc];
        ChessColor mover = colorOf(piece);
        boolean pawn = Character.toLowerCase(piece) == 'p';

        // En-passant capture.
        if (pawn && fc != tc && captured == '.' && tr == enPassantRow && tc == enPassantCol) {
            captured = board[fr][tc];
            board[fr][tc] = '.';
        }

        updateCastlingRights(piece, fr, fc, captured, tr, tc);
        board[tr][tc] = piece;
        board[fr][fc] = '.';

        // Move rook during castling.
        if (Character.toLowerCase(piece) == 'k' && Math.abs(tc - fc) == 2) {
            if (tc == 6) {
                board[tr][5] = board[tr][7];
                board[tr][7] = '.';
            } else {
                board[tr][3] = board[tr][0];
                board[tr][0] = '.';
            }
        }

        // Promotion defaults to queen when the client omits the piece.
        if (pawn && (tr == 0 || tr == 7)) {
            char promotion = move.promotion() == null ? 'q' : Character.toLowerCase(move.promotion());
            board[tr][tc] = mover == ChessColor.WHITE ? Character.toUpperCase(promotion) : promotion;
        }

        enPassantRow = enPassantCol = -1;
        if (pawn && Math.abs(tr - fr) == 2) {
            enPassantRow = (fr + tr) / 2;
            enPassantCol = fc;
        }

        halfMoveClock = pawn || captured != '.' ? 0 : halfMoveClock + 1;
        if (mover == ChessColor.BLACK) {
            fullMoveNumber++;
        }
        turn = turn.opposite();
        repetitions.merge(positionKey(), 1, Integer::sum);
    }

    private void updateCastlingRights(char piece, int fr, int fc, char captured, int tr, int tc) {
        switch (piece) {
            case 'K' -> { whiteKingSide = false; whiteQueenSide = false; }
            case 'k' -> { blackKingSide = false; blackQueenSide = false; }
            case 'R' -> {
                if (fr == 7 && fc == 0) whiteQueenSide = false;
                if (fr == 7 && fc == 7) whiteKingSide = false;
            }
            case 'r' -> {
                if (fr == 0 && fc == 0) blackQueenSide = false;
                if (fr == 0 && fc == 7) blackKingSide = false;
            }
            default -> { }
        }
        if (captured == 'R' && tr == 7 && tc == 0) whiteQueenSide = false;
        if (captured == 'R' && tr == 7 && tc == 7) whiteKingSide = false;
        if (captured == 'r' && tr == 0 && tc == 0) blackQueenSide = false;
        if (captured == 'r' && tr == 0 && tc == 7) blackKingSide = false;
    }

    private boolean hasAnyLegalMove(ChessColor color) {
        for (int fr = 0; fr < 8; fr++) {
            for (int fc = 0; fc < 8; fc++) {
                char piece = board[fr][fc];
                if (piece == '.' || colorOf(piece) != color) continue;
                for (int tr = 0; tr < 8; tr++) {
                    for (int tc = 0; tc < 8; tc++) {
                        String from = square(fr, fc);
                        String to = square(tr, tc);
                        if (Character.toLowerCase(piece) == 'p' && (tr == 0 || tr == 7)) {
                            for (char promotion : new char[]{'q', 'r', 'b', 'n'}) {
                                if (isLegal(new Move(from, to, promotion), color)) return true;
                            }
                        } else if (isLegal(new Move(from, to, null), color)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public synchronized boolean isKingInCheck(ChessColor color) {
        char king = color == ChessColor.WHITE ? 'K' : 'k';
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                if (board[row][col] == king) {
                    return isSquareAttacked(row, col, color.opposite());
                }
            }
        }
        return true;
    }

    private boolean isSquareAttacked(int targetRow, int targetCol, ChessColor attacker) {
        int pawnRow = targetRow + (attacker == ChessColor.WHITE ? 1 : -1);
        char pawn = attacker == ChessColor.WHITE ? 'P' : 'p';
        if (inside(pawnRow, targetCol - 1) && board[pawnRow][targetCol - 1] == pawn) return true;
        if (inside(pawnRow, targetCol + 1) && board[pawnRow][targetCol + 1] == pawn) return true;

        char knight = attacker == ChessColor.WHITE ? 'N' : 'n';
        int[][] knightSteps = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
        for (int[] step : knightSteps) {
            int row = targetRow + step[0], col = targetCol + step[1];
            if (inside(row, col) && board[row][col] == knight) return true;
        }

        if (rayAttacked(targetRow, targetCol, attacker, new int[][]{{-1,-1},{-1,1},{1,-1},{1,1}}, 'b')) return true;
        if (rayAttacked(targetRow, targetCol, attacker, new int[][]{{-1,0},{1,0},{0,-1},{0,1}}, 'r')) return true;

        char king = attacker == ChessColor.WHITE ? 'K' : 'k';
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int row = targetRow + dr, col = targetCol + dc;
                if (inside(row, col) && board[row][col] == king) return true;
            }
        }
        return false;
    }

    private boolean rayAttacked(int row, int col, ChessColor attacker, int[][] directions, char required) {
        for (int[] direction : directions) {
            int r = row + direction[0], c = col + direction[1];
            while (inside(r, c)) {
                char piece = board[r][c];
                if (piece != '.') {
                    if (colorOf(piece) == attacker) {
                        char lower = Character.toLowerCase(piece);
                        if (lower == required || lower == 'q') return true;
                    }
                    break;
                }
                r += direction[0];
                c += direction[1];
            }
        }
        return false;
    }

    private boolean pathClear(int fr, int fc, int tr, int tc) {
        int dr = Integer.compare(tr, fr);
        int dc = Integer.compare(tc, fc);
        int row = fr + dr, col = fc + dc;
        while (row != tr || col != tc) {
            if (board[row][col] != '.') return false;
            row += dr;
            col += dc;
        }
        return true;
    }

    private boolean insufficientMaterial() {
        int bishops = 0;
        int knights = 0;
        int bishopSquareColor = -1;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                char piece = Character.toLowerCase(board[row][col]);
                if (piece == '.' || piece == 'k') continue;
                if (piece == 'p' || piece == 'r' || piece == 'q') return false;
                if (piece == 'n') knights++;
                if (piece == 'b') {
                    bishops++;
                    int color = (row + col) % 2;
                    if (bishopSquareColor == -1) bishopSquareColor = color;
                    else if (bishopSquareColor != color) return false;
                }
            }
        }
        if (bishops == 0 && knights <= 1) return true;
        return knights == 0 && bishops > 0;
    }

    public synchronized String toFen() {
        StringBuilder fen = new StringBuilder();
        for (int row = 0; row < 8; row++) {
            int empty = 0;
            for (int col = 0; col < 8; col++) {
                char piece = board[row][col];
                if (piece == '.') {
                    empty++;
                } else {
                    if (empty > 0) fen.append(empty);
                    empty = 0;
                    fen.append(piece);
                }
            }
            if (empty > 0) fen.append(empty);
            if (row < 7) fen.append('/');
        }
        fen.append(turn == ChessColor.WHITE ? " w " : " b ");
        String rights = castlingRights();
        fen.append(rights.isEmpty() ? "-" : rights).append(' ');
        fen.append(enPassantRow < 0 ? "-" : square(enPassantRow, enPassantCol));
        fen.append(' ').append(halfMoveClock).append(' ').append(fullMoveNumber);
        return fen.toString();
    }

    private String positionKey() {
        String fen = toFen();
        String[] fields = fen.split(" ");
        return String.join(" ", fields[0], fields[1], fields[2], fields[3]);
    }

    private String castlingRights() {
        StringBuilder rights = new StringBuilder();
        if (whiteKingSide) rights.append('K');
        if (whiteQueenSide) rights.append('Q');
        if (blackKingSide) rights.append('k');
        if (blackQueenSide) rights.append('q');
        return rights.toString();
    }

    public synchronized ChessColor turn() {
        return turn;
    }

    public synchronized char pieceAt(String square) {
        if (!validSquare(square)) return '.';
        return board[row(square)][col(square)];
    }

    private static ChessColor colorOf(char piece) {
        return Character.isUpperCase(piece) ? ChessColor.WHITE : ChessColor.BLACK;
    }

    private static boolean validSquare(String square) {
        return square != null && square.length() == 2
                && square.charAt(0) >= 'a' && square.charAt(0) <= 'h'
                && square.charAt(1) >= '1' && square.charAt(1) <= '8';
    }

    private static int row(String square) {
        return 8 - (square.charAt(1) - '0');
    }

    private static int col(String square) {
        return square.charAt(0) - 'a';
    }

    private static String square(int row, int col) {
        return String.valueOf((char) ('a' + col)) + (8 - row);
    }

    private static boolean inside(int row, int col) {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }
}
