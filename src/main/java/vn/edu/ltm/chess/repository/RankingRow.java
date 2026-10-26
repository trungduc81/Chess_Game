package vn.edu.ltm.chess.repository;

public record RankingRow(int rank, String username, int elo,
                         int wins, int totalMatches, double winRate) { }
