package vn.edu.ltm.chess.chess;

public record Move(String from, String to, Character promotion) {
    public Move {
        from = normalize(from);
        to = normalize(to);
        promotion = promotion == null ? null : Character.toLowerCase(promotion);
    }

    private static String normalize(String square) {
        return square == null ? null : square.trim().toLowerCase();
    }
}
