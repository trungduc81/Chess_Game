package vn.edu.ltm.chess.repository;

public record RankingSort(Field field, boolean ascending) {
    public enum Field {
        ELO,
        TOTAL_MATCHES,
        WIN_RATE
    }
}
