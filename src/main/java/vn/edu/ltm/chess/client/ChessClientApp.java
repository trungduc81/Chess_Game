package vn.edu.ltm.chess.client;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import vn.edu.ltm.chess.client.ui.AdminDashboardView;
import vn.edu.ltm.chess.client.ui.AdminMatchesView;
import vn.edu.ltm.chess.client.ui.AdminUsersView;
import vn.edu.ltm.chess.client.ui.DialogOverlay;
import vn.edu.ltm.chess.client.ui.GameView;
import vn.edu.ltm.chess.client.ui.LobbyView;
import vn.edu.ltm.chess.client.ui.LoginView;
import vn.edu.ltm.chess.client.ui.ProfileView;
import vn.edu.ltm.chess.client.ui.RankingView;
import vn.edu.ltm.chess.client.ui.ReplayView;
import vn.edu.ltm.chess.client.ui.SoundManager;
import vn.edu.ltm.chess.config.AppConfig;
import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.MessageType;
import vn.edu.ltm.chess.protocol.NetworkMessage;

import java.util.Objects;

/** Điều phối kết nối mạng, message và chuyển đổi giữa các JavaFX view. */
public final class ChessClientApp extends Application {
    /**
     * Kích thước chung cho mọi màn hình khi cửa sổ ở trạng thái khôi phục
     * (Restore).
     */
    private static final double WINDOW_WIDTH = 1120;
    private static final double WINDOW_HEIGHT = 760;
    private static final double MIN_WIDTH = 700;
    private static final double MIN_HEIGHT = 480;

    private final AppConfig config = AppConfig.load();

    private Stage stage;
    /**
     * Một Scene duy nhất dùng chung, chỉ thay root khi chuyển màn hình để giữ
     * nguyên kích thước.
     */
    private Scene scene;
    private final ClientNetworkService network = new ClientNetworkService(Platform::runLater, this::onMessage);
    private String username;
    private String role = "PLAYER";
    private int currentElo = 1200;
    private LoginView loginView;
    private LobbyView lobbyView;
    private GameView gameView;
    private RankingView rankingView;
    private ReplayView replayView;
    private AdminDashboardView adminDashboardView;
    private AdminUsersView adminUsersView;
    private AdminMatchesView adminMatchesView;
    private JsonNode latestOnlinePlayers = JsonProtocol.MAPPER.createArrayNode();
    private JsonNode cachedHistoryRows;
    private JsonNode selectedHistoryMatch;
    private JsonNode cachedAdminMatches;
    private JsonNode adminMatchFilter = JsonProtocol.object();
    private JsonNode selectedAdminMatch;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        SoundManager.initialize();
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setResizable(true);
        showLogin();
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);
        stage.centerOnScreen();
        stage.setMaximized(true);
        stage.show();
    }

    private void showLogin() {
        stopGameView();
        stopReplayView();
        lobbyView = null;
        rankingView = null;
        adminDashboardView = null;
        adminUsersView = null;
        adminMatchesView = null;
        loginView = new LoginView(this::authenticate);
        showScreen(loginView.root(), stylesheet("/styles/login.css", "Không tìm thấy CSS đăng nhập"));
    }

    private void authenticate(boolean register, String user, String password) {
        LoginView submittingView = loginView;
        JsonNode data = JsonProtocol.object().put("username", user.trim()).put("password", password);
        network.authenticate(config.serverHost(), config.serverPort(),
                NetworkMessage.of(register ? MessageType.REGISTER : MessageType.LOGIN, data),
                error -> submittingView.showError("Không kết nối được tới máy chủ: " + error));
    }

    private void onMessage(NetworkMessage message) {
        switch (message.type()) {
            case LOGIN_SUCCESS -> {
                if (loginView != null && loginView.isSubmitting())
                    loginView.completeRequest();
                username = message.data().path("username").asText();
                role = message.data().path("role").asText("PLAYER").toUpperCase();
                currentElo = message.data().path("elo").asInt(1200);
                latestOnlinePlayers = JsonProtocol.MAPPER.createArrayNode();
                if ("ADMIN".equals(role)) {
                    showAdminDashboard(JsonProtocol.object());
                    request(MessageType.ADMIN_DASHBOARD_REQUEST);
                } else {
                    showLobby();
                }
            }
            case ONLINE_LIST -> {
                updateCurrentElo(message.data());
                latestOnlinePlayers = message.data().deepCopy();
                if (lobbyView != null)
                    lobbyView.updateOnline(message.data());
            }
            case CHALLENGE_RECEIVED -> handleChallenge(message.data());
            case CHALLENGE_RESULT -> {
                boolean accepted = message.data().path("accepted").asBoolean(false);
                String responseMessage = message.data().path("message").asText(
                        accepted ? "Đã chấp nhận" : "Đối thủ từ chối");
                if (responseMessage.contains("hết hạn")) {
                    DialogOverlay.warning(popupOwner(), "LỜI THÁCH ĐẤU HẾT HẠN", responseMessage);
                } else if (!accepted) {
                    DialogOverlay.notice(popupOwner(), DialogOverlay.Tone.DANGER, "✕",
                            "THÁCH ĐẤU BỊ TỪ CHỐI", responseMessage);
                } else {
                    DialogOverlay.notice(popupOwner(), DialogOverlay.Tone.DEFAULT, "⚔",
                            "THÁCH ĐẤU ĐƯỢC CHẤP NHẬN", responseMessage);
                }
            }
            case SUCCESS -> {
                String responseMessage = message.data().path("message").asText("Thao tác thành công");
                if (loginView != null && loginView.isSubmitting()) {
                    loginView.showSuccess(responseMessage);
                } else if (responseMessage.contains("thách đấu")) {
                    DialogOverlay.notice(popupOwner(), DialogOverlay.Tone.DEFAULT, "⚔",
                            "ĐÃ GỬI LỜI THÁCH ĐẤU", responseMessage);
                } else if (responseMessage.contains("đề nghị hòa")) {
                    DialogOverlay.notice(popupOwner(), DialogOverlay.Tone.DRAW, "🤝",
                            "ĐÃ GỬI ĐỀ NGHỊ HÒA", responseMessage);
                } else {
                    DialogOverlay.information(popupOwner(), "THÔNG BÁO", responseMessage);
                }
            }
            case GAME_STARTED -> {
                showGame(message.data());
                SoundManager.play(SoundManager.Effect.GAME_START);
            }
            case GAME_STATE -> {
                if (gameView != null)
                    gameView.updateState(message.data());
            }
            case LEGAL_MOVES_RESPONSE -> {
                if (gameView != null)
                    gameView.showLegalMoves(message.data());
            }
            case MOVE_REJECTED -> {
                SoundManager.play(SoundManager.Effect.ILLEGAL);
                DialogOverlay.warning(popupOwner(),
                        "THÔNG BÁO", message.data().path("message").asText());
            }
            case ERROR -> {
                String responseMessage = message.data().path("message").asText("Yêu cầu không hợp lệ");
                if (loginView != null && loginView.isSubmitting()) {
                    loginView.showError(responseMessage);
                } else {
                    DialogOverlay.warning(popupOwner(), "THÔNG BÁO", responseMessage);
                }
            }
            case CHAT_RECEIVED -> {
                if (gameView != null)
                    gameView.addChatMessage(message.data());
            }
            case DRAW_RECEIVED -> {
                if (gameView != null)
                    gameView.handleDrawOffer(message.data());
            }
            case DRAW_RESULT, REMATCH_STATUS -> {
                boolean closed = message.data().path("closed").asBoolean(false);
                if (closed && gameView != null && gameView.shouldDeferClosedStatus())
                    break;
                if (closed) {
                    showLobby();
                } else if (gameView == null || gameView.terminalPopupOpen()) {
                    break;
                } else if (message.type() == MessageType.DRAW_RESULT) {
                    DialogOverlay.notice(popupOwner(), DialogOverlay.Tone.DANGER, "✕",
                            "ĐỀ NGHỊ HÒA BỊ TỪ CHỐI",
                            message.data().path("message").asText());
                } else {
                    DialogOverlay.notice(popupOwner(), DialogOverlay.Tone.DEFAULT, "↻",
                            "TRẠNG THÁI TÁI ĐẤU",
                            message.data().path("message").asText());
                }
            }
            case GAME_OVER -> {
                if (gameView != null)
                    gameView.handleGameOver(message.data());
            }
            case HISTORY_RESPONSE -> {
                cachedHistoryRows = message.data().deepCopy();
                showProfile(cachedHistoryRows);
            }
            case MATCH_DETAIL_RESPONSE -> {
                JsonNode detail = message.data();
                JsonNode moves = detail.isArray() ? detail : detail.path("moves");
                String pgn = detail.isArray() ? "" : detail.path("pgn").asText("");
                if (selectedAdminMatch != null) {
                    showAdminReplay(selectedAdminMatch, moves, pgn);
                } else if (selectedHistoryMatch != null) {
                    showReplay(selectedHistoryMatch, moves, pgn);
                }
            }
            case RANKING_RESPONSE -> {
                if (rankingView == null)
                    showRanking(message.data());
                else
                    rankingView.updateRows(message.data());
            }
            case ADMIN_DASHBOARD_RESPONSE -> {
                if (adminDashboardView == null) showAdminDashboard(message.data());
                else adminDashboardView.updateStatistics(message.data());
            }
            case ADMIN_USERS_RESPONSE -> {
                if (adminUsersView == null) showAdminUsers(message.data());
                else adminUsersView.updateRows(message.data());
            }
            case ADMIN_MATCHES_RESPONSE -> {
                cachedAdminMatches = message.data().deepCopy();
                if (adminMatchesView == null) showAdminMatches(message.data());
                else adminMatchesView.updateRows(message.data());
            }
            case ADMIN_ACTION_RESULT -> {
                DialogOverlay.information(popupOwner(), "THAO TÁC QUẢN TRỊ",
                        message.data().path("message").asText("Thao tác thành công"));
                if (adminUsersView != null) request(MessageType.ADMIN_USERS_REQUEST);
            }
            case DISCONNECTED -> handleDisconnect(message.data());
            default -> {
            }
        }
    }

    private void showLobby() {
        stopGameView();
        stopReplayView();
        rankingView = null;
        cachedHistoryRows = null;
        selectedHistoryMatch = null;
        lobbyView = new LobbyView(username, currentElo, new LobbyView.Actions() {
            @Override
            public void challenge(String target) {
                send(NetworkMessage.of(MessageType.CHALLENGE_SEND,
                        JsonProtocol.object().put("target", target)));
            }

            @Override
            public void showProfile() {
                send(NetworkMessage.of(MessageType.HISTORY_REQUEST, JsonProtocol.object()));
            }

            @Override
            public void showRanking() {
                send(NetworkMessage.of(MessageType.RANKING_REQUEST, JsonProtocol.object()));
            }

            @Override
            public void logout() {
                ChessClientApp.this.logout();
            }
        });
        lobbyView.updateOnline(latestOnlinePlayers);
        showScreen(lobbyView.root(), stylesheet("/styles/lobby.css", "Không tìm thấy CSS sảnh chờ"));
    }

    private void showProfile(JsonNode rows) {
        stopReplayView();
        lobbyView = null;
        rankingView = null;
        ProfileView profileView = new ProfileView(username, currentElo, rows, this::showLobby, match -> {
            selectedHistoryMatch = match.deepCopy();
            send(NetworkMessage.of(MessageType.MATCH_DETAIL_REQUEST,
                    JsonProtocol.object().put("matchId", match.path("id").asLong())));
        });
        showRecordsScene(profileView.root());
    }

    private void showRanking(JsonNode rows) {
        stopReplayView();
        lobbyView = null;
        rankingView = new RankingView(username, rows, this::showLobby,
                request -> send(NetworkMessage.of(MessageType.RANKING_REQUEST, request)));
        showRecordsScene(rankingView.root());
    }

    private void showReplay(JsonNode match, JsonNode moves, String pgn) {
        stopReplayView();
        lobbyView = null;
        rankingView = null;
        replayView = new ReplayView(match, moves, pgn, this::returnToProfile);
        showRecordsScene(replayView.root());
    }

    private void returnToProfile() {
        if (cachedHistoryRows != null) {
            showProfile(cachedHistoryRows);
        } else {
            send(NetworkMessage.of(MessageType.HISTORY_REQUEST, JsonProtocol.object()));
        }
    }

    private void showAdminDashboard(JsonNode statistics) {
        stopGameView();
        stopReplayView();
        clearPlayerViews();
        adminUsersView = null;
        adminMatchesView = null;
        selectedAdminMatch = null;
        adminDashboardView = new AdminDashboardView(username, statistics,
                new AdminDashboardView.Actions() {
                    @Override public void showUsers() { request(MessageType.ADMIN_USERS_REQUEST); }
                    @Override public void showMatches() {
                        adminMatchFilter = JsonProtocol.object();
                        request(MessageType.ADMIN_MATCHES_REQUEST);
                    }
                    @Override public void refresh() { request(MessageType.ADMIN_DASHBOARD_REQUEST); }
                    @Override public void logout() { ChessClientApp.this.logout(); }
                });
        showAdminScene(adminDashboardView.root());
    }

    private void showAdminUsers(JsonNode rows) {
        stopReplayView();
        clearPlayerViews();
        adminDashboardView = null;
        adminMatchesView = null;
        selectedAdminMatch = null;
        adminUsersView = new AdminUsersView(username, rows, new AdminUsersView.Actions() {
            @Override public void back() { request(MessageType.ADMIN_DASHBOARD_REQUEST); }
            @Override public void refresh() { request(MessageType.ADMIN_USERS_REQUEST); }
            @Override public void lock(String target, String reason) {
                send(NetworkMessage.of(MessageType.ADMIN_LOCK_USER,
                        JsonProtocol.object().put("username", target).put("reason", reason)));
            }
            @Override public void unlock(String target) {
                send(NetworkMessage.of(MessageType.ADMIN_UNLOCK_USER,
                        JsonProtocol.object().put("username", target)));
            }
            @Override public void kick(String target) {
                send(NetworkMessage.of(MessageType.ADMIN_KICK_USER,
                        JsonProtocol.object().put("username", target)));
            }
        });
        showAdminScene(adminUsersView.root());
    }

    private void showAdminMatches(JsonNode rows) {
        stopReplayView();
        clearPlayerViews();
        adminDashboardView = null;
        adminUsersView = null;
        selectedAdminMatch = null;
        adminMatchesView = new AdminMatchesView(rows, new AdminMatchesView.Actions() {
            @Override public void back() { request(MessageType.ADMIN_DASHBOARD_REQUEST); }
            @Override public void refresh() {
                send(NetworkMessage.of(MessageType.ADMIN_MATCHES_REQUEST, adminMatchFilter));
            }
            @Override public void filter(JsonNode filter) {
                adminMatchFilter = filter.deepCopy();
                send(NetworkMessage.of(MessageType.ADMIN_MATCHES_REQUEST, adminMatchFilter));
            }
            @Override public void replay(JsonNode match) {
                selectedAdminMatch = match.deepCopy();
                send(NetworkMessage.of(MessageType.MATCH_DETAIL_REQUEST,
                        JsonProtocol.object().put("matchId", match.path("id").asLong())));
            }
        }, adminMatchFilter);
        showAdminScene(adminMatchesView.root());
    }

    private void showAdminReplay(JsonNode match, JsonNode moves, String pgn) {
        stopReplayView();
        clearPlayerViews();
        adminDashboardView = null;
        adminUsersView = null;
        adminMatchesView = null;
        replayView = new ReplayView(match, moves, pgn,
                "Quay lại Danh sách trận đấu", this::returnToAdminMatches);
        showAdminScene(replayView.root(),
                stylesheet("/styles/records.css", "Không tìm thấy CSS replay"));
    }

    private void returnToAdminMatches() {
        if (cachedAdminMatches != null) showAdminMatches(cachedAdminMatches);
        else request(MessageType.ADMIN_MATCHES_REQUEST);
    }

    private void showRecordsScene(Parent root) {
        showScreen(root,
                stylesheet("/styles/lobby.css", "Không tìm thấy CSS sảnh chờ"),
                stylesheet("/styles/records.css", "Không tìm thấy CSS lịch sử và xếp hạng"));
    }

    private void showAdminScene(Parent root, String... additionalStylesheets) {
        String[] stylesheets = new String[additionalStylesheets.length + 1];
        stylesheets[0] = stylesheet("/styles/admin.css", "Không tìm thấy CSS quản trị");
        System.arraycopy(additionalStylesheets, 0, stylesheets, 1, additionalStylesheets.length);
        showScreen(root, stylesheets);
    }

    private void request(MessageType type) {
        send(NetworkMessage.of(type, JsonProtocol.object()));
    }

    private void clearPlayerViews() {
        lobbyView = null;
        rankingView = null;
        selectedHistoryMatch = null;
    }

    private void updateCurrentElo(JsonNode players) {
        if (username == null)
            return;
        for (JsonNode player : players) {
            if (username.equals(player.path("username").asText())) {
                currentElo = player.path("elo").asInt(currentElo);
                return;
            }
        }
    }

    private void handleChallenge(JsonNode data) {
        String challengeId = data.path("challengeId").asText();
        if (gameView != null || "ADMIN".equals(role)) {
            answerChallenge(challengeId, false);
            return;
        }
        SoundManager.play(SoundManager.Effect.NOTIFY);
        DialogOverlay.challenge(popupOwner(), data.path("challenger").asText(),
                data.path("challengerElo").asInt(1200),
                accept -> answerChallenge(challengeId, accept));
    }

    private void answerChallenge(String challengeId, boolean accept) {
        send(NetworkMessage.of(MessageType.CHALLENGE_RESPONSE,
                JsonProtocol.object().put("challengeId", challengeId).put("accept", accept)));
    }

    private void showGame(JsonNode data) {
        stopGameView();
        stopReplayView();
        gameView = new GameView(username, data, this::send, this::showLobby);
        lobbyView = null;
        rankingView = null;
        cachedHistoryRows = null;
        selectedHistoryMatch = null;
        var gameCss = getClass().getResource("/styles/game.css");
        if (gameCss != null) {
            showScreen(gameView.root(), gameCss.toExternalForm());
        } else {
            showScreen(gameView.root());
        }
    }

    /**
     * Chuyển màn hình bằng cách thay root của Scene dùng chung. Cửa sổ giữ nguyên
     * trạng thái hiện tại (phóng to / khôi phục / thu nhỏ) và kích thước hiện tại.
     */
    private void showScreen(Parent root, String... stylesheets) {
        if (scene == null) {
            scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
            stage.setScene(scene);
        } else {
            scene.setRoot(root);
        }
        scene.getStylesheets().setAll(stylesheets);
    }

    private String stylesheet(String path, String missingMessage) {
        return Objects.requireNonNull(getClass().getResource(path), missingMessage).toExternalForm();
    }

    private void logout() {
        network.disconnect(true);
        clearSession();
        showLogin();
    }

    private void handleDisconnect(JsonNode data) {
        SoundManager.play(SoundManager.Effect.DISCONNECT);
        stopGameView();
        network.disconnect(false);
        clearSession();
        showLogin();
        DialogOverlay.disconnected(loginView.root(), data.path("message").asText(), () -> {
        });
    }

    private void clearSession() {
        username = null;
        role = "PLAYER";
        currentElo = 1200;
        latestOnlinePlayers = JsonProtocol.MAPPER.createArrayNode();
        cachedHistoryRows = null;
        selectedHistoryMatch = null;
        cachedAdminMatches = null;
        selectedAdminMatch = null;
        adminDashboardView = null;
        adminUsersView = null;
        adminMatchesView = null;
    }

    private void send(NetworkMessage message) {
        if (!network.isConnected()) {
            DialogOverlay.disconnected(popupOwner(), "Chưa kết nối tới máy chủ", this::showLogin);
            return;
        }
        network.send(message);
    }

    private Node popupOwner() {
        return stage.getScene().getRoot();
    }

    private void stopGameView() {
        if (gameView != null) {
            gameView.stop();
            gameView = null;
        }
    }

    private void stopReplayView() {
        if (replayView != null) {
            replayView.stop();
            replayView = null;
        }
    }

    @Override
    public void stop() throws Exception {
        stopGameView();
        stopReplayView();
        network.close();
        SoundManager.shutdown();
    }
}
