package vn.edu.ltm.chess.rating;

public final class EloCalculator {
    public static final int DEFAULT_ELO = 1200;
    public static final int K_FACTOR = 32;

    private EloCalculator() { }

    public static int whiteDelta(int whiteElo, int blackElo, double whiteScore) {
        double expected = 1.0 / (1.0 + Math.pow(10.0, (blackElo - whiteElo) / 400.0));
        return (int) Math.round(K_FACTOR * (whiteScore - expected));
    }
}
