package vn.edu.ltm.chess.repository;

public record AdminUserRow(long id, String username, int elo, int totalMatches,
                           String accountStatus, String role, long createdAt,
                           String lockedReason) { }
