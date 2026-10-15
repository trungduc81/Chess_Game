package vn.edu.ltm.chess.repository;

public record StoredMove(int ply, String from, String to, Character promotion,
                         String notation, String fenAfter, long playedAt) { }
