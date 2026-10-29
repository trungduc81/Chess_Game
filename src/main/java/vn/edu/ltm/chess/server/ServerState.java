package vn.edu.ltm.chess.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Một trạng thái dùng chung trong tiến trình server; cùng khóa cho đăng nhập và ghép trận. */
final class ServerState {
    final Object matchmakingLock = new Object();
    final ConcurrentMap<String, ClientConnection> online = new ConcurrentHashMap<>();
    final ConcurrentMap<String, PlayerStatus> statuses = new ConcurrentHashMap<>();
    final ConcurrentMap<String, Challenge> challenges = new ConcurrentHashMap<>();
    final ConcurrentMap<String, GameSession> playerGames = new ConcurrentHashMap<>();
}
