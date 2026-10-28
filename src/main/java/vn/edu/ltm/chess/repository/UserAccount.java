package vn.edu.ltm.chess.repository;

public record UserAccount(long id, String username, int elo, String role,
                          String accountStatus, String lockedReason) { }
