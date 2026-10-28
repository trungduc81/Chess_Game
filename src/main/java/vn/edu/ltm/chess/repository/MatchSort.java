package vn.edu.ltm.chess.repository;

/** Tiêu chí sắp xếp trận đấu được cho phép phía server. */
public record MatchSort(Field field, boolean ascending) {
    public enum Field {
        TIME,
        MOVE_COUNT,
        ID
    }
}
