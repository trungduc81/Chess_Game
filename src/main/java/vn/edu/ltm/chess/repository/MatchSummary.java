package vn.edu.ltm.chess.repository;

public record MatchSummary(long id, String whitePlayer, String blackPlayer, String result,
                           String reason, int moveCount, String initialFen,
                           int whiteEloDelta, int blackEloDelta, long startedAt, long endedAt) { }
