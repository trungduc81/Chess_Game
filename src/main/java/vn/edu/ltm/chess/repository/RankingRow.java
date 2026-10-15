package vn.edu.ltm.chess.repository;

public record RankingRow(int rank, String username, int elo,
                         Double averageOpponentElo, Double averageWinningPlies,
                         Double averageLosingPlies) { }
