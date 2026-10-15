package vn.edu.ltm.chess.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.edu.ltm.chess.chess.*;
import vn.edu.ltm.chess.protocol.*;
import vn.edu.ltm.chess.repository.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** TCP server trung tâm: mọi trạng thái và quyết định nghiệp vụ đều được xác nhận tại đây. */
public final class ChessServer implements AutoCloseable {
    private final int port;
    private final ChessRepository repository;
    private final long moveTimeoutMillis;
    private final long heartbeatIntervalMillis;
    private final long heartbeatTimeoutMillis;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ConcurrentMap<String, ClientConnection> online = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PlayerStatus> statuses = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, GameSession> playerGames = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ServerSocket serverSocket;

    public ChessServer(int port, ChessRepository repository) {
        this(port, repository, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(15));
    }

    public ChessServer(int port, ChessRepository repository, Duration moveTimeout,
                       Duration heartbeatInterval, Duration heartbeatTimeout) {
        this.port = port;
        this.repository = repository;
        this.moveTimeoutMillis = moveTimeout.toMillis();
        this.heartbeatIntervalMillis = heartbeatInterval.toMillis();
        this.heartbeatTimeoutMillis = heartbeatTimeout.toMillis();
    }

    public void start() throws Exception {
        repository.initialize();
        serverSocket = new ServerSocket(port);
        running = true;
        scheduler.scheduleAtFixedRate(this::checkTimeouts, 200, 200, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::heartbeat, heartbeatIntervalMillis,
                heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
        System.out.println("Chess server đang chạy tại cổng " + port);
        while (running) {
            Socket socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            clients.submit(new ClientConnection(socket, this));
        }
    }

    public void handle(ClientConnection client, NetworkMessage message) {
        try {
            client.markSeen();
            if (message.type() == MessageType.PONG) return;
            if (message.type() == MessageType.PING) {
                client.send(NetworkMessage.reply(MessageType.PONG, message.requestId(), JsonProtocol.object()));
                return;
            }
            if (message.type() == MessageType.REGISTER) { register(client, message); return; }
            if (message.type() == MessageType.LOGIN) { login(client, message); return; }
            if (!authenticated(client, message)) return;

            switch (message.type()) {
                case LOGOUT -> logout(client);
                case CHALLENGE_SEND -> challenge(client, message);
                case CHALLENGE_RESPONSE -> challengeResponse(client, message);
                case MOVE -> move(client, message);
                case CHAT_SEND -> chat(client, message);
                case DRAW_OFFER -> drawOffer(client, message);
                case DRAW_RESPONSE -> drawResponse(client, message);
                case RESIGN -> resign(client, message);
                case EXIT_GAME -> exitGame(client, message);
                case REMATCH_RESPONSE -> rematch(client, message);
                case HISTORY_REQUEST -> client.send(NetworkMessage.reply(MessageType.HISTORY_RESPONSE,
                        message.requestId(), JsonProtocol.value(repository.history(client.username()))));
                case MATCH_DETAIL_REQUEST -> matchDetail(client, message);
                case RANKING_REQUEST -> client.send(NetworkMessage.reply(MessageType.RANKING_RESPONSE,
                        message.requestId(), JsonProtocol.value(repository.ranking())));
                default -> error(client, message, "Loại thông điệp không hợp lệ trong trạng thái hiện tại");
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            error(client, message, "Lỗi server: " + safeMessage(exception));
        }
    }

    private boolean authenticated(ClientConnection client, NetworkMessage message) {
        if (client.username() == null) {
            error(client, message, "Bạn phải đăng nhập trước");
            return false;
        }
        if (message.sessionToken() == null || !message.sessionToken().equals(client.sessionToken())) {
            error(client, message, "Phiên đăng nhập không hợp lệ");
            return false;
        }
        return true;
    }

    private void register(ClientConnection client, NetworkMessage message) throws SQLException {
        if (client.username() != null) {
            error(client, message, "Hãy đăng xuất trước khi đăng ký tài khoản khác");
            return;
        }
        String username = text(message.data(), "username").trim();
        String password = text(message.data(), "password");
        if (!username.matches("[A-Za-z0-9_]{3,40}") || password.length() < 6) {
            error(client, message, "Tên đăng nhập gồm 3-40 ký tự chữ/số/_ và mật khẩu tối thiểu 6 ký tự");
            return;
        }
        if (!repository.register(username, password)) {
            error(client, message, "Tên đăng nhập đã tồn tại");
            return;
        }
        success(client, message, "Đăng ký thành công, ELO khởi tạo là 1200");
    }

    private void login(ClientConnection client, NetworkMessage message) throws SQLException {
        if (client.username() != null) { error(client, message, "Kết nối này đã đăng nhập"); return; }
        String username = text(message.data(), "username").trim();
        Optional<UserAccount> account = repository.authenticate(username, text(message.data(), "password"));
        if (account.isEmpty()) { error(client, message, "Sai tên đăng nhập hoặc mật khẩu"); return; }
        if (online.putIfAbsent(username, client) != null) {
            error(client, message, "Tài khoản đang đăng nhập ở máy khác");
            return;
        }
        String token = UUID.randomUUID().toString();
        client.username(username);
        client.sessionToken(token);
        statuses.put(username, PlayerStatus.FREE);
        ObjectNode data = JsonProtocol.object().put("username", username)
                .put("elo", account.get().elo()).put("sessionToken", token);
        client.send(new NetworkMessage(MessageType.LOGIN_SUCCESS, message.requestId(), token, null, data));
        broadcastOnlineList();
    }

    private void logout(ClientConnection client) {
        disconnected(client);
        try { client.close(); } catch (IOException ignored) { }
    }

    private synchronized void challenge(ClientConnection client, NetworkMessage message) {
        String from = client.username();
        String target = text(message.data(), "target");
        if (from.equals(target) || !online.containsKey(target)) {
            error(client, message, "Đối thủ không online");
            return;
        }
        if (statuses.get(from) != PlayerStatus.FREE || statuses.get(target) != PlayerStatus.FREE) {
            error(client, message, "Một trong hai người chơi đang bận");
            return;
        }
        boolean alreadyPending = challenges.values().stream().anyMatch(c ->
                c.challenger().equals(from) || c.challenged().equals(from)
                        || c.challenger().equals(target) || c.challenged().equals(target));
        if (alreadyPending) {
            error(client, message, "Một người chơi đang có lời thách đấu chờ xử lý");
            return;
        }
        Challenge challenge = new Challenge(UUID.randomUUID().toString(), from, target, System.currentTimeMillis());
        challenges.put(challenge.id(), challenge);
        online.get(target).send(NetworkMessage.of(MessageType.CHALLENGE_RECEIVED,
                JsonProtocol.object().put("challengeId", challenge.id()).put("challenger", from)));
        success(client, message, "Đã gửi lời thách đấu");
    }

    private synchronized void challengeResponse(ClientConnection client, NetworkMessage message) {
        String id = text(message.data(), "challengeId");
        Challenge challenge = challenges.remove(id);
        if (challenge == null || !challenge.challenged().equals(client.username())) {
            error(client, message, "Lời thách đấu không còn hiệu lực");
            return;
        }
        ClientConnection challenger = online.get(challenge.challenger());
        boolean accept = message.data().path("accept").asBoolean(false);
        if (!accept || challenger == null) {
            if (challenger != null) challenger.send(NetworkMessage.of(MessageType.CHALLENGE_RESULT,
                    JsonProtocol.object().put("accepted", false).put("opponent", client.username())
                            .put("message", client.username() + " đã từ chối lời thách đấu")));
            return;
        }
        if (statuses.get(challenge.challenger()) != PlayerStatus.FREE
                || statuses.get(challenge.challenged()) != PlayerStatus.FREE) {
            error(client, message, "Một người chơi không còn rảnh");
            return;
        }
        createGame(challenge.challenged(), challenge.challenger());
    }

    private synchronized void createGame(String white, String black) {
        GameSession game = new GameSession(white, black, moveTimeoutMillis);
        playerGames.put(white, game);
        playerGames.put(black, game);
        statuses.put(white, PlayerStatus.BUSY);
        statuses.put(black, PlayerStatus.BUSY);
        sendGameStarted(game, white);
        sendGameStarted(game, black);
        broadcastOnlineList();
    }

    private void sendGameStarted(GameSession game, String player) {
        ClientConnection connection = online.get(player);
        if (connection == null) return;
        ObjectNode data = gameState(game);
        data.put("yourColor", game.colorOf(player).name());
        data.put("white", game.white()).put("black", game.black());
        connection.send(new NetworkMessage(MessageType.GAME_STARTED, UUID.randomUUID().toString(),
                connection.sessionToken(), game.id(), data));
    }

    private void move(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null) return;
        String promotionText = message.data().path("promotion").asText("");
        Character promotion = promotionText.isBlank() ? null : promotionText.charAt(0);
        Move move = new Move(text(message.data(), "from"), text(message.data(), "to"), promotion);
        GameSession.TurnResult result = game.move(client.username(), move);
        if (result.timedOutPlayer() != null) {
            client.send(NetworkMessage.reply(MessageType.MOVE_REJECTED, message.requestId(),
                    JsonProtocol.object().put("message", "Nước đi đến sau thời hạn")));
            finishClaimed(game, winnerWhenLoses(game, result.timedOutPlayer()),
                    result.timedOutPlayer() + " hết 30 giây");
            return;
        }
        if (!result.accepted()) {
            ObjectNode data = JsonProtocol.object().put("message", result.error());
            if (result.moveResult() != null) data.put("fen", result.moveResult().fen());
            client.send(NetworkMessage.reply(MessageType.MOVE_REJECTED, message.requestId(), data));
            return;
        }
        sendBoth(game, MessageType.GAME_STATE, gameState(game));
        if (result.finished()) {
            finishClaimed(game, result.moveResult().outcome().name(), result.moveResult().reason());
        }
    }

    private void chat(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null || game.phase() != GameSession.Phase.PLAYING) return;
        String content = text(message.data(), "message").trim();
        if (content.isBlank() || content.length() > 300) {
            error(client, message, "Tin nhắn phải có 1-300 ký tự");
            return;
        }
        sendBoth(game, MessageType.CHAT_RECEIVED, JsonProtocol.object()
                .put("from", client.username()).put("message", content).put("sentAt", System.currentTimeMillis()));
    }

    private void drawOffer(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null) return;
        if (!game.offerDraw(client.username())) {
            error(client, message, "Không thể gửi yêu cầu hòa lúc này");
            return;
        }
        ClientConnection opponent = online.get(game.opponentOf(client.username()));
        if (opponent != null) opponent.send(NetworkMessage.of(MessageType.DRAW_RECEIVED,
                JsonProtocol.object().put("from", client.username())));
    }

    private void drawResponse(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null) return;
        boolean accept = message.data().path("accept").asBoolean(false);
        synchronized (game) {
            if (!game.canAnswerDraw(client.username())) {
                error(client, message, "Không có yêu cầu hòa hợp lệ");
                return;
            }
            game.clearDrawOffer();
            if (accept && !game.claimFinish()) return;
        }
        if (accept) finishClaimed(game, "DRAW", "Hai bên đồng ý hòa");
        else sendBoth(game, MessageType.DRAW_RESULT, JsonProtocol.object().put("accepted", false)
                .put("message", client.username() + " đã từ chối hòa"));
    }

    private void resign(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game != null && game.claimFinish()) {
            finishClaimed(game, winnerWhenLoses(game, client.username()), client.username() + " đầu hàng");
        }
    }

    private void exitGame(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null) return;
        if (game.claimFinish()) {
            finishClaimed(game, winnerWhenLoses(game, client.username()),
                    client.username() + " thoát trận", false);
        }
        game.voteRematch(client.username(), false);
        closeGame(game, client.username() + " không tiếp tục");
    }

    private void rematch(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null || !game.voteRematch(client.username(),
                message.data().path("accept").asBoolean(false))) {
            error(client, message, "Không thể trả lời tái đấu lúc này");
            return;
        }
        Boolean decision = game.rematchDecision();
        if (decision == null) {
            sendBoth(game, MessageType.REMATCH_STATUS,
                    JsonProtocol.object().put("message", client.username() + " đã đồng ý tái đấu"));
        } else if (!decision) {
            closeGame(game, "Một người chơi không tiếp tục");
        } else {
            String oldWhite = game.white(), oldBlack = game.black();
            game.close();
            createGame(oldBlack, oldWhite);
        }
    }

    private void matchDetail(ClientConnection client, NetworkMessage message) throws SQLException {
        long matchId = message.data().path("matchId").asLong(-1);
        if (matchId < 1) { error(client, message, "Mã trận không hợp lệ"); return; }
        client.send(NetworkMessage.reply(MessageType.MATCH_DETAIL_RESPONSE, message.requestId(),
                JsonProtocol.value(repository.matchMoves(matchId, client.username()))));
    }

    private void finishClaimed(GameSession game, String result, String reason) {
        finishClaimed(game, result, reason, true);
    }

    private void finishClaimed(GameSession game, String result, String reason, boolean rematchAllowed) {
        if (!game.beginPersistence()) return;
        SavedMatch saved = null;
        String persistenceError = null;
        try {
            saved = repository.saveMatch(game.id(), game.white(), game.black(), result, reason,
                    game.startedAt(), System.currentTimeMillis(), game.moves());
        } catch (SQLException exception) {
            persistenceError = safeMessage(exception);
            exception.printStackTrace();
        }
        game.finish();
        ObjectNode data = JsonProtocol.object().put("gameId", game.id()).put("result", result)
                .put("reason", reason).put("rematchAllowed", rematchAllowed)
                .put("matchId", saved == null ? -1 : saved.matchId());
        if (saved != null) {
            data.put("whiteEloBefore", saved.whiteEloBefore()).put("whiteEloAfter", saved.whiteEloAfter())
                    .put("whiteEloDelta", saved.whiteEloDelta()).put("blackEloBefore", saved.blackEloBefore())
                    .put("blackEloAfter", saved.blackEloAfter()).put("blackEloDelta", saved.blackEloDelta());
        }
        if (persistenceError != null) data.put("persistenceWarning", persistenceError);
        sendBoth(game, MessageType.GAME_OVER, data);
        broadcastOnlineList();
    }

    private synchronized void closeGame(GameSession game, String message) {
        game.close();
        playerGames.remove(game.white(), game);
        playerGames.remove(game.black(), game);
        if (online.containsKey(game.white())) statuses.put(game.white(), PlayerStatus.FREE);
        if (online.containsKey(game.black())) statuses.put(game.black(), PlayerStatus.FREE);
        sendBoth(game, MessageType.REMATCH_STATUS,
                JsonProtocol.object().put("closed", true).put("message", message));
        broadcastOnlineList();
    }

    private void checkTimeouts() {
        try {
            long now = System.currentTimeMillis();
            for (GameSession game : new HashSet<>(playerGames.values())) {
                String loser = game.claimTimeout(now);
                if (loser != null) finishClaimed(game, winnerWhenLoses(game, loser), loser + " hết 30 giây");
            }
            challenges.values().removeIf(challenge -> {
                if (now - challenge.createdAt() <= 30_000) return false;
                ClientConnection challenger = online.get(challenge.challenger());
                if (challenger != null) challenger.send(NetworkMessage.of(MessageType.CHALLENGE_RESULT,
                        JsonProtocol.object().put("accepted", false)
                                .put("message", "Lời thách đấu đã hết hạn")));
                return true;
            });
        } catch (Exception exception) {
            System.err.println("Lỗi kiểm tra timeout: " + safeMessage(exception));
        }
    }

    private void heartbeat() {
        long now = System.currentTimeMillis();
        for (ClientConnection connection : online.values()) {
            if (now - connection.lastSeenAt() > heartbeatTimeoutMillis) {
                System.err.println("Heartbeat timeout: " + connection.username() + " " + connection.remoteAddress());
                try { connection.close(); } catch (IOException ignored) { }
            } else {
                connection.send(NetworkMessage.of(MessageType.PING,
                        JsonProtocol.object().put("serverTime", now)));
            }
        }
    }

    public void disconnected(ClientConnection client) {
        String username = client.username();
        if (username == null || !online.remove(username, client)) return;
        statuses.remove(username);
        challenges.values().removeIf(c -> c.challenger().equals(username) || c.challenged().equals(username));
        GameSession game = playerGames.get(username);
        if (game != null) {
            if (game.claimFinish()) {
                finishClaimed(game, winnerWhenLoses(game, username), username + " mất kết nối", false);
            }
            closeGame(game, username + " đã mất kết nối");
        }
        broadcastOnlineList();
    }

    private GameSession activeGame(ClientConnection client, NetworkMessage message) {
        GameSession game = playerGames.get(client.username());
        if (game == null) {
            error(client, message, "Bạn không ở trong ván đấu nào");
            return null;
        }
        if (message.gameId() == null || !message.gameId().equals(game.id())) {
            error(client, message, "Mã phiên đấu không hợp lệ hoặc đã hết hạn");
            return null;
        }
        return game;
    }

    private ObjectNode gameState(GameSession game) {
        ObjectNode data = JsonProtocol.object().put("gameId", game.id()).put("fen", game.fen())
                .put("turn", game.turn().name()).put("deadlineEpochMs", game.deadlineEpochMs());
        data.set("moves", JsonProtocol.value(game.moves()));
        return data;
    }

    private void sendBoth(GameSession game, MessageType type, JsonNode data) {
        ClientConnection white = online.get(game.white());
        ClientConnection black = online.get(game.black());
        if (white != null) white.send(new NetworkMessage(type, UUID.randomUUID().toString(),
                white.sessionToken(), game.id(), data));
        if (black != null) black.send(new NetworkMessage(type, UUID.randomUUID().toString(),
                black.sessionToken(), game.id(), data));
    }

    private void broadcastOnlineList() {
        ArrayNode list = JsonProtocol.MAPPER.createArrayNode();
        online.keySet().stream().sorted().forEach(username -> {
            ObjectNode row = JsonProtocol.object().put("username", username)
                    .put("status", statuses.getOrDefault(username, PlayerStatus.FREE).name());
            try { row.put("elo", repository.elo(username)); }
            catch (SQLException ignored) { row.put("elo", 1200); }
            list.add(row);
        });
        NetworkMessage message = NetworkMessage.of(MessageType.ONLINE_LIST, list);
        online.values().forEach(connection -> connection.send(message));
    }

    private static String winnerWhenLoses(GameSession game, String loser) {
        return loser.equals(game.white()) ? "BLACK_WIN" : "WHITE_WIN";
    }

    private static String text(JsonNode data, String field) { return data.path(field).asText(""); }
    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
    private static void success(ClientConnection client, NetworkMessage request, String message) {
        client.send(NetworkMessage.reply(MessageType.SUCCESS, request.requestId(),
                JsonProtocol.object().put("message", message)));
    }
    private static void error(ClientConnection client, NetworkMessage request, String message) {
        client.send(NetworkMessage.reply(MessageType.ERROR, request.requestId(),
                JsonProtocol.object().put("message", message)));
    }

    @Override public void close() throws Exception {
        running = false;
        scheduler.shutdownNow();
        clients.shutdownNow();
        if (serverSocket != null) serverSocket.close();
    }
}
