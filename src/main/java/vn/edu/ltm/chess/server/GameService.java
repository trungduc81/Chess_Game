package vn.edu.ltm.chess.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.edu.ltm.chess.chess.Move;
import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.MessageType;
import vn.edu.ltm.chess.protocol.NetworkMessage;
import vn.edu.ltm.chess.repository.ChessRepository;
import vn.edu.ltm.chess.repository.SavedMatch;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static vn.edu.ltm.chess.server.ServerMessages.*;

/** Thách đấu, phiên chơi, đồng hồ, kết thúc và tái đấu. Giữ nguyên các khóa của GameSession. */
final class GameService {
    private final ServerState state;
    private final ChessRepository repository;
    private final ServerMessages messages;
    private final ExecutorService clientExecutor;
    private final long moveTimeoutMillis;

    GameService(ServerState state, ChessRepository repository, ServerMessages messages,
                ExecutorService clientExecutor, long moveTimeoutMillis) {
        this.state = state;
        this.repository = repository;
        this.messages = messages;
        this.clientExecutor = clientExecutor;
        this.moveTimeoutMillis = moveTimeoutMillis;
    }

    void challenge(ClientConnection client, NetworkMessage message) throws SQLException {
        String from = client.username();
        String target = text(message.data(), "target");
        int challengerElo = repository.elo(from);
        Challenge pending = null;
        ClientConnection targetConnection = null;
        String rejection = null;
        synchronized (state.matchmakingLock) {
            if (from.equals(target) || !state.online.containsKey(target)) {
                rejection = "Đối thủ không online";
            } else if (state.statuses.get(from) != PlayerStatus.FREE
                    || state.statuses.get(target) != PlayerStatus.FREE
                    || state.playerGames.containsKey(from) || state.playerGames.containsKey(target)) {
                rejection = "Một trong hai người chơi đang bận";
            } else {
                boolean alreadyPending = state.challenges.values().stream()
                        .anyMatch(c -> c.challenger().equals(from) || c.challenged().equals(from)
                                || c.challenger().equals(target) || c.challenged().equals(target));
                if (alreadyPending) {
                    rejection = "Một người chơi đang có lời thách đấu chờ xử lý";
                } else {
                    pending = new Challenge(UUID.randomUUID().toString(), from, target,
                            System.currentTimeMillis());
                    state.challenges.put(pending.id(), pending);
                    targetConnection = state.online.get(target);
                }
            }
        }
        if (rejection != null) {
            error(client, message, rejection);
            return;
        }
        targetConnection.send(NetworkMessage.of(MessageType.CHALLENGE_RECEIVED,
                JsonProtocol.object().put("challengeId", pending.id()).put("challenger", from)
                        .put("challengerElo", challengerElo)));
        success(client, message, "Đã gửi lời thách đấu đến " + target);
    }

    void challengeResponse(ClientConnection client, NetworkMessage message) {
        String id = text(message.data(), "challengeId");
        boolean accept = message.data().path("accept").asBoolean(false);
        Challenge challenge;
        ClientConnection challenger = null;
        GameSession game = null;
        String rejection = null;
        synchronized (state.matchmakingLock) {
            challenge = state.challenges.get(id);
            if (challenge == null || !challenge.challenged().equals(client.username())) {
                rejection = "Lời thách đấu không còn hiệu lực";
            } else {
                challenger = state.online.get(challenge.challenger());
                if (!accept || challenger == null) {
                    state.challenges.remove(id, challenge);
                } else if (state.statuses.get(challenge.challenger()) != PlayerStatus.FREE
                        || state.statuses.get(challenge.challenged()) != PlayerStatus.FREE
                        || state.playerGames.containsKey(challenge.challenger())
                        || state.playerGames.containsKey(challenge.challenged())) {
                    state.challenges.remove(id, challenge);
                    rejection = "Một người chơi không còn rảnh";
                } else {
                    state.challenges.remove(id, challenge);
                    game = createGameLocked(challenge.challenged(), challenge.challenger());
                }
            }
        }
        if (rejection != null) {
            error(client, message, rejection);
            return;
        }
        if (!accept || challenger == null) {
            if (challenger != null)
                challenger.send(NetworkMessage.of(MessageType.CHALLENGE_RESULT,
                        JsonProtocol.object().put("accepted", false).put("opponent", client.username())
                                .put("message", client.username() + " đã từ chối lời thách đấu")));
            return;
        }
        announceGame(game);
    }

    private GameSession createGameLocked(String white, String black) {
        GameSession game = new GameSession(white, black, moveTimeoutMillis);
        state.playerGames.put(white, game);
        state.playerGames.put(black, game);
        state.statuses.put(white, PlayerStatus.BUSY);
        state.statuses.put(black, PlayerStatus.BUSY);
        return game;
    }

    private void announceGame(GameSession game) {
        sendGameStarted(game, game.white());
        sendGameStarted(game, game.black());
        messages.broadcastOnlineList();
    }

    private void sendGameStarted(GameSession game, String player) {
        ClientConnection connection = state.online.get(player);
        if (connection == null)
            return;
        ObjectNode data = gameState(game);
        data.put("yourColor", game.colorOf(player).name());
        data.put("white", game.white()).put("black", game.black());
        connection.send(new NetworkMessage(MessageType.GAME_STARTED, UUID.randomUUID().toString(),
                connection.sessionToken(), game.id(), data));
    }

    void move(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null)
            return;
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
            if (result.moveResult() != null)
                data.put("fen", result.moveResult().fen());
            client.send(NetworkMessage.reply(MessageType.MOVE_REJECTED, message.requestId(), data));
            return;
        }
        messages.sendBoth(game, MessageType.GAME_STATE, gameState(game));
        if (result.finished()) {
            finishClaimed(game, result.moveResult().outcome().name(), result.moveResult().reason());
        }
    }

    void legalMoves(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null)
            return;
        String from = text(message.data(), "from").trim().toLowerCase(Locale.ROOT);
        ObjectNode data = JsonProtocol.object().put("from", from);
        ArrayNode destinations = data.putArray("destinations");
        game.legalDestinations(client.username(), from).forEach(destinations::add);
        client.send(NetworkMessage.reply(MessageType.LEGAL_MOVES_RESPONSE,
                message.requestId(), data));
    }

    void chat(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null || game.phase() != GameSession.Phase.PLAYING)
            return;
        String content = text(message.data(), "message").trim();
        if (content.isBlank() || content.length() > 300) {
            error(client, message, "Tin nhắn phải có 1-300 ký tự");
            return;
        }
        messages.sendBoth(game, MessageType.CHAT_RECEIVED, JsonProtocol.object()
                .put("from", client.username()).put("message", content).put("sentAt", System.currentTimeMillis()));
    }

    void drawOffer(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null)
            return;
        if (!game.offerDraw(client.username())) {
            error(client, message, "Không thể gửi yêu cầu hòa lúc này");
            return;
        }
        ClientConnection opponent = state.online.get(game.opponentOf(client.username()));
        if (opponent != null) {
            opponent.send(NetworkMessage.of(MessageType.DRAW_RECEIVED,
                    JsonProtocol.object().put("from", client.username())));
            success(client, message, "Đã gửi đề nghị hòa đến " + opponent.username());
        }
    }

    void drawResponse(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null)
            return;
        boolean accept = message.data().path("accept").asBoolean(false);
        synchronized (game) {
            if (!game.canAnswerDraw(client.username())) {
                error(client, message, "Không có yêu cầu hòa hợp lệ");
                return;
            }
            game.clearDrawOffer();
            if (accept && !game.claimFinish())
                return;
        }
        if (accept)
            finishClaimed(game, "DRAW", "Hai bên đồng ý hòa");
        else {
            ClientConnection opponent = state.online.get(game.opponentOf(client.username()));
            if (opponent != null)
                opponent.send(NetworkMessage.of(MessageType.DRAW_RESULT,
                        JsonProtocol.object().put("accepted", false)
                                .put("message", client.username() + " đã từ chối hòa")));
        }
    }

    void resign(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game != null && game.claimFinish()) {
            finishClaimed(game, winnerWhenLoses(game, client.username()), client.username() + " đầu hàng");
        }
    }

    void exitGame(ClientConnection client, NetworkMessage message) {
        GameSession game = activeGame(client, message);
        if (game == null)
            return;
        if (game.claimFinish()) {
            finishClaimed(game, winnerWhenLoses(game, client.username()),
                    client.username() + " thoát trận", false);
        }
        game.voteRematch(client.username(), false);
        closeGame(game, client.username() + " không tiếp tục");
    }

    void rematch(ClientConnection client, NetworkMessage message) {
        GameSession game = state.playerGames.get(client.username());
        if (game == null) {
            client.send(NetworkMessage.reply(MessageType.REMATCH_STATUS, message.requestId(),
                    JsonProtocol.object().put("closed", true)
                            .put("message", "Phiên đấu đã kết thúc")));
            return;
        }
        if (message.gameId() == null || !message.gameId().equals(game.id())) {
            error(client, message, "Mã phiên đấu không hợp lệ hoặc đã hết hạn");
            return;
        }
        if (!game.voteRematch(client.username(),
                message.data().path("accept").asBoolean(false))) {
            error(client, message, "Không thể trả lời tái đấu lúc này");
            return;
        }
        Boolean decision = game.rematchDecision();
        if (decision == null) {
            ClientConnection opponent = state.online.get(game.opponentOf(client.username()));
            if (opponent != null)
                opponent.send(NetworkMessage.of(MessageType.REMATCH_STATUS,
                        JsonProtocol.object().put("message", client.username() + " đã đồng ý tái đấu")));
        } else if (!decision) {
            closeGame(game, "Một người chơi không tiếp tục");
        } else {
            GameSession rematch = null;
            synchronized (state.matchmakingLock) {
                if (state.playerGames.get(game.white()) == game && state.playerGames.get(game.black()) == game
                        && state.online.containsKey(game.white()) && state.online.containsKey(game.black())) {
                    game.close();
                    rematch = createGameLocked(game.black(), game.white());
                }
            }
            if (rematch == null) {
                closeGame(game, "Không thể bắt đầu tái đấu vì một người chơi đã rời phòng");
            } else {
                announceGame(rematch);
            }
        }
    }

    private void finishClaimed(GameSession game, String result, String reason) {
        finishClaimed(game, result, reason, true);
    }

    private void finishClaimed(GameSession game, String result, String reason, boolean rematchAllowed) {
        if (!game.beginPersistence())
            return;
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
        if (persistenceError != null)
            data.put("persistenceWarning", persistenceError);
        messages.sendBoth(game, MessageType.GAME_OVER, data);
        messages.broadcastOnlineList();
    }

    private void closeGame(GameSession game, String message) {
        boolean removed;
        synchronized (state.matchmakingLock) {
            boolean whiteRemoved = state.playerGames.remove(game.white(), game);
            boolean blackRemoved = state.playerGames.remove(game.black(), game);
            removed = whiteRemoved || blackRemoved;
            if (!removed)
                return;
            game.close();
            if (state.online.containsKey(game.white()))
                state.statuses.put(game.white(), PlayerStatus.FREE);
            if (state.online.containsKey(game.black()))
                state.statuses.put(game.black(), PlayerStatus.FREE);
        }
        messages.sendBoth(game, MessageType.REMATCH_STATUS,
                JsonProtocol.object().put("closed", true).put("message", message));
        messages.broadcastOnlineList();
    }

    void checkTimeouts() {
        try {
            long now = System.currentTimeMillis();
            for (GameSession game : new HashSet<>(state.playerGames.values())) {
                String loser = game.claimTimeout(now);
                if (loser != null) {
                    String result = winnerWhenLoses(game, loser);
                    clientExecutor.submit(() -> finishClaimed(game, result, loser + " hết 30 giây"));
                }
            }
            List<ClientConnection> expiredChallengers = new ArrayList<>();
            synchronized (state.matchmakingLock) {
                for (Challenge challenge : new ArrayList<>(state.challenges.values())) {
                    if (now - challenge.createdAt() <= 30_000)
                        continue;
                    if (!state.challenges.remove(challenge.id(), challenge))
                        continue;
                    ClientConnection challenger = state.online.get(challenge.challenger());
                    if (challenger != null)
                        expiredChallengers.add(challenger);
                }
            }
            for (ClientConnection challenger : expiredChallengers) {
                clientExecutor.submit(() -> challenger.send(NetworkMessage.of(MessageType.CHALLENGE_RESULT,
                        JsonProtocol.object().put("accepted", false)
                                .put("message", "Lời thách đấu đã hết hạn"))));
            }
        } catch (Exception exception) {
            System.err.println("Lỗi kiểm tra timeout: " + safeMessage(exception));
        }
    }

    private GameSession activeGame(ClientConnection client, NetworkMessage message) {
        GameSession game = state.playerGames.get(client.username());
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
        GameSession.StateSnapshot snapshot = game.stateSnapshot();
        ObjectNode data = JsonProtocol.object().put("gameId", game.id()).put("fen", snapshot.fen())
                .put("turn", snapshot.turn().name()).put("deadlineEpochMs", snapshot.deadlineEpochMs())
                .put("inCheck", snapshot.inCheck())
                .put("remainingTurnTimeMs",
                        Math.max(0, snapshot.deadlineEpochMs() - System.currentTimeMillis()));
        data.set("moves", JsonProtocol.value(snapshot.moves()));
        return data;
    }

    private static String winnerWhenLoses(GameSession game, String loser) {
        return loser.equals(game.white()) ? "BLACK_WIN" : "WHITE_WIN";
    }

    void playerDisconnected(GameSession game, String username) {
        if (game.claimFinish()) {
            finishClaimed(game, winnerWhenLoses(game, username), username + " mất kết nối", false);
        }
        closeGame(game, username + " đã mất kết nối");
    }
}
