package vn.edu.ltm.chess.repository;

public record SavedMatch(long matchId, int whiteEloBefore, int whiteEloAfter, int whiteEloDelta,
                         int blackEloBefore, int blackEloAfter, int blackEloDelta,
                         boolean newlyCreated) { }
