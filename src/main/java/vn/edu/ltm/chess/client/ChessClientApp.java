package vn.edu.ltm.chess.client;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import vn.edu.ltm.chess.config.AppConfig;
import vn.edu.ltm.chess.protocol.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class ChessClientApp extends Application {
    private final AppConfig config = AppConfig.load();
    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private Stage stage;
    private NetworkClient network;
    private String username;
    private TableView<JsonNode> onlineTable;
    private BoardView boardView;
    private Label turnLabel;
    private Label myTimerLabel;
    private Label opponentTimerLabel;
    private TableView<MovePair> gameMoveTable;
    private ListView<String> chatList;
    private TextField chatInput;
    private String myColor;
    private String currentTurn;
    private String selectedSquare;
    private long deadline;
    private Timeline timer;

    @Override public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("Cờ vua mạng - Java TCP/JavaFX");
        stage.setMinWidth(700);
        stage.setMinHeight(480);
        showLogin();
        stage.show();
    }

    private void showLogin() {
        TextField user = new TextField();
        PasswordField password = new PasswordField();
        Label status = new Label();
        Button login = new Button("Đăng nhập");
        Button register = new Button("Đăng ký");
        GridPane form = new GridPane(10, 10);
        form.setAlignment(Pos.CENTER);
        form.addRow(0, new Label("Tài khoản:"), user);
        form.addRow(1, new Label("Mật khẩu:"), password);
        HBox actions = new HBox(10, login, register);
        actions.setAlignment(Pos.CENTER);
        VBox root = new VBox(16, new Label("HỆ THỐNG CỜ VUA TRỰC TUYẾN"), form, actions, status);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        login.setDefaultButton(true);
        login.setOnAction(event -> authenticate(false, user.getText(), password.getText(), status));
        register.setOnAction(event -> authenticate(true, user.getText(), password.getText(), status));
        stage.setScene(new Scene(root, 760, 520));
    }

    private void authenticate(boolean register, String user, String password, Label status) {
        try {
            if (network == null) {
                network = new NetworkClient(config.serverHost(), config.serverPort());
                network.listener(message -> Platform.runLater(() -> onMessage(message)));
            }
            JsonNode data = JsonProtocol.object().put("username", user.trim()).put("password", password);
            network.send(NetworkMessage.of(register ? MessageType.REGISTER : MessageType.LOGIN, data));
            status.setText(register ? "Đang đăng ký..." : "Đang đăng nhập...");
        } catch (Exception exception) {
            status.setText("Không kết nối được tới máy chủ: " + exception.getMessage());
            network = null;
        }
    }

    private void onMessage(NetworkMessage message) {
        switch (message.type()) {
            case LOGIN_SUCCESS -> {
                username = message.data().path("username").asText();
                showLobby();
            }
            case ONLINE_LIST -> updateOnline(message.data());
            case CHALLENGE_RECEIVED -> incomingChallenge(message.data());
            case CHALLENGE_RESULT, SUCCESS -> toast(message.data().path("message").asText(
                    message.data().path("accepted").asBoolean() ? "Đã chấp nhận" : "Đối thủ từ chối"));
            case GAME_STARTED -> showGame(message.data());
            case GAME_STATE -> updateGame(message.data());
            case MOVE_REJECTED, ERROR -> alert(Alert.AlertType.WARNING, "Thông báo", message.data().path("message").asText());
            case CHAT_RECEIVED -> chatList.getItems().add(message.data().path("from").asText() + ": " + message.data().path("message").asText());
            case DRAW_RECEIVED -> incomingDraw(message.data());
            case DRAW_RESULT, REMATCH_STATUS -> {
                toast(message.data().path("message").asText());
                if (message.data().path("closed").asBoolean(false)) showLobby();
            }
            case GAME_OVER -> gameOver(message.data());
            case HISTORY_RESPONSE -> showHistory(message.data());
            case MATCH_DETAIL_RESPONSE -> showReplay(message.data());
            case RANKING_RESPONSE -> showRanking(message.data());
            case DISCONNECTED -> {
                if (timer != null) timer.stop();
                network = null;
                username = null;
                alert(Alert.AlertType.ERROR, "Mất kết nối", message.data().path("message").asText());
                showLogin();
            }
            default -> { }
        }
    }

    private void showLobby() {
        onlineTable = new TableView<>();
        TableColumn<JsonNode, String> name = column("Người chơi", n -> n.path("username").asText());
        TableColumn<JsonNode, String> score = column("ELO", n -> n.path("elo").asText("1200"));
        TableColumn<JsonNode, String> status = column("Trạng thái", n -> "BUSY".equals(n.path("status").asText()) ? "Đang bận" : "Đang rỗi");
        onlineTable.getColumns().setAll(name, score, status);
        onlineTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Button challenge = new Button("Thách đấu người đã chọn");
        Button history = new Button("Lịch sử ván đấu");
        Button ranking = new Button("Bảng xếp hạng");
        Button logout = new Button("Đăng xuất");
        challenge.setOnAction(event -> {
            JsonNode target = onlineTable.getSelectionModel().getSelectedItem();
            if (target == null) { toast("Hãy chọn một người chơi"); return; }
            network.send(NetworkMessage.of(MessageType.CHALLENGE_SEND,
                    JsonProtocol.object().put("target", target.path("username").asText())));
        });
        history.setOnAction(event -> network.send(NetworkMessage.of(MessageType.HISTORY_REQUEST, JsonProtocol.object())));
        ranking.setOnAction(event -> network.send(NetworkMessage.of(MessageType.RANKING_REQUEST, JsonProtocol.object())));
        logout.setOnAction(event -> {
            network.send(NetworkMessage.of(MessageType.LOGOUT, JsonProtocol.object()));
            try { network.close(); } catch (Exception ignored) { }
            network = null; username = null; showLogin();
        });
        Label title = new Label("Xin chào " + username + " — Danh sách người chơi online");
        title.setFont(Font.font(20));
        VBox root = new VBox(12, title, onlineTable, new HBox(10, challenge, history, ranking, logout));
        root.setPadding(new Insets(18));
        VBox.setVgrow(onlineTable, Priority.ALWAYS);
        stage.setScene(new Scene(root, 780, 540));
    }

    private void updateOnline(JsonNode data) {
        if (onlineTable == null) return;
        List<JsonNode> players = new ArrayList<>();
        data.forEach(node -> { if (!username.equals(node.path("username").asText())) players.add(node); });
        onlineTable.setItems(FXCollections.observableArrayList(players));
    }

    private void incomingChallenge(JsonNode data) {
        String challenger = data.path("challenger").asText();
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, challenger + " thách đấu bạn. Chấp nhận?", ButtonType.OK, ButtonType.CANCEL);
        dialog.setHeaderText("Lời thách đấu");
        boolean accept = dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        network.send(NetworkMessage.of(MessageType.CHALLENGE_RESPONSE,
                JsonProtocol.object().put("challengeId", data.path("challengeId").asText()).put("accept", accept)));
    }

    private void showGame(JsonNode data) {
        myColor = data.path("yourColor").asText();
        String whitePlayer = data.path("white").asText();
        String blackPlayer = data.path("black").asText();
        String opponent = username.equals(whitePlayer) ? blackPlayer : whitePlayer;
        boardView = new BoardView(this::squareClicked);
        turnLabel = new Label();
        turnLabel.setMaxWidth(Double.MAX_VALUE);
        turnLabel.setAlignment(Pos.CENTER);
        turnLabel.getStyleClass().add("turn-label");
        myTimerLabel = new Label("00:30");
        opponentTimerLabel = new Label("00:30");
        myTimerLabel.getStyleClass().add("clock-label");
        opponentTimerLabel.getStyleClass().add("clock-label");
        gameMoveTable = createGameMoveTable();
        chatList = new ListView<>();
        chatList.getStyleClass().add("game-list");
        chatInput = new TextField();
        chatInput.setPromptText("Tin nhắn trong trận...");
        Button send = new Button("Gửi");
        send.getStyleClass().add("send-button");
        send.setOnAction(event -> sendChat());
        chatInput.setOnAction(event -> sendChat());
        Button draw = new Button("Xin hòa");
        Button resign = new Button("Đầu hàng");
        Button exit = new Button("Thoát trận");
        draw.getStyleClass().addAll("action-button", "draw-button");
        resign.getStyleClass().addAll("action-button", "resign-button");
        exit.getStyleClass().addAll("action-button", "exit-button");
        draw.setOnAction(event -> network.send(NetworkMessage.of(MessageType.DRAW_OFFER, JsonProtocol.object())));
        resign.setOnAction(event -> { if (confirm("Bạn chắc chắn muốn đầu hàng?")) network.send(NetworkMessage.of(MessageType.RESIGN, JsonProtocol.object())); });
        exit.setOnAction(event -> { if (confirm("Thoát trận sẽ bị xử thua. Tiếp tục?")) network.send(NetworkMessage.of(MessageType.EXIT_GAME, JsonProtocol.object())); });

        HBox opponentCard = playerCard(opponent,
                "WHITE".equals(myColor) ? "ĐEN" : "TRẮNG", opponentTimerLabel);
        HBox myCard = playerCard(username,
                "WHITE".equals(myColor) ? "TRẮNG" : "ĐEN", myTimerLabel);
        VBox boardColumn = new VBox(8, opponentCard, boardView, myCard);
        boardColumn.setAlignment(Pos.CENTER);
        boardColumn.setPrefWidth(466);

        HBox chatComposer = new HBox(8, chatInput, send);
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        VBox chatPanel = new VBox(8, panelTitle("TRÒ CHUYỆN"), chatList, chatComposer);
        chatPanel.getStyleClass().add("game-panel");
        chatPanel.setPrefHeight(250);
        VBox.setVgrow(chatList, Priority.ALWAYS);

        VBox movesPanel = new VBox(8, panelTitle("LỊCH SỬ NƯỚC ĐI"), gameMoveTable);
        movesPanel.getStyleClass().add("game-panel");
        VBox.setVgrow(gameMoveTable, Priority.ALWAYS);

        HBox actions = new HBox(8, draw, resign, exit);
        for (Button button : List.of(draw, resign, exit)) {
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
        }

        VBox rightColumn = new VBox(10, chatPanel, movesPanel, actions, turnLabel);
        rightColumn.setMinWidth(285);
        rightColumn.setPrefWidth(320);
        VBox.setVgrow(movesPanel, Priority.ALWAYS);

        HBox gameContent = new HBox(18, boardColumn, rightColumn);
        gameContent.setAlignment(Pos.CENTER);
        HBox.setHgrow(rightColumn, Priority.ALWAYS);
        StackPane root = new StackPane(gameContent);
        root.getStyleClass().add("game-root");
        Scene gameScene = new Scene(root, 930, 620);
        var stylesheet = ChessClientApp.class.getResource("/styles/game.css");
        if (stylesheet != null) gameScene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(gameScene);
        updateGame(data);
        if (timer != null) timer.stop();
        timer = new Timeline(new KeyFrame(Duration.millis(100), event -> updateClock()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private static HBox playerCard(String player, String color, Label clock) {
        Label identity = new Label(player + "  •  " + color);
        identity.getStyleClass().add("player-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox card = new HBox(10, identity, spacer, clock);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPrefWidth(466);
        card.setMaxWidth(466);
        card.getStyleClass().add("player-card");
        return card;
    }

    private static Label panelTitle(String text) {
        Label title = new Label(text);
        title.getStyleClass().add("panel-title");
        return title;
    }

    private static TableView<MovePair> createGameMoveTable() {
        TableView<MovePair> table = new TableView<>();
        TableColumn<MovePair, String> number = new TableColumn<>("#");
        TableColumn<MovePair, String> white = new TableColumn<>("TRẮNG");
        TableColumn<MovePair, String> black = new TableColumn<>("ĐEN");
        number.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().number()));
        white.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().white()));
        black.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().black()));
        number.setMaxWidth(48);
        number.setMinWidth(42);
        table.getColumns().setAll(number, white, black);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Chưa có nước đi"));
        table.getStyleClass().add("moves-table");
        return table;
    }

    private void updateGame(JsonNode data) {
        if (boardView == null) return;
        boardView.render(data.path("fen").asText(), "BLACK".equals(myColor));
        selectedSquare = null;
        String turn = data.path("turn").asText();
        currentTurn = turn;
        turnLabel.setText("Lượt: " + ("WHITE".equals(turn) ? "Trắng" : "Đen"));
        deadline = data.path("deadlineEpochMs").asLong();
        List<JsonNode> moves = new ArrayList<>();
        data.path("moves").forEach(moves::add);
        List<MovePair> rows = new ArrayList<>();
        for (int index = 0; index < moves.size(); index += 2) {
            String whiteMove = moves.get(index).path("notation").asText();
            String blackMove = index + 1 < moves.size() ? moves.get(index + 1).path("notation").asText() : "";
            rows.add(new MovePair(String.valueOf(index / 2 + 1), whiteMove, blackMove));
        }
        gameMoveTable.setItems(FXCollections.observableArrayList(rows));
        if (!rows.isEmpty()) gameMoveTable.scrollTo(rows.size() - 1);
    }

    private void squareClicked(String square) {
        if (selectedSquare == null) {
            selectedSquare = square;
            boardView.select(square);
            return;
        }
        ObjectNodeBuilder move = new ObjectNodeBuilder().put("from", selectedSquare).put("to", square);
        char piece = boardView.pieceAt(selectedSquare);
        if ((piece == 'P' && square.endsWith("8")) || (piece == 'p' && square.endsWith("1"))) {
            ChoiceDialog<String> promotion = new ChoiceDialog<>("Q", "Q", "R", "B", "N");
            promotion.setHeaderText("Chọn quân phong cấp");
            move.put("promotion", promotion.showAndWait().orElse("Q").toLowerCase());
        }
        network.send(NetworkMessage.of(MessageType.MOVE, move.node));
        selectedSquare = null;
        boardView.clearSelection();
    }

    private void sendChat() {
        String content = chatInput.getText().trim();
        if (content.isEmpty()) return;
        network.send(NetworkMessage.of(MessageType.CHAT_SEND, JsonProtocol.object().put("message", content)));
        chatInput.clear();
    }

    private void incomingDraw(JsonNode data) {
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, data.path("from").asText() + " đề nghị hòa. Chấp nhận?", ButtonType.OK, ButtonType.CANCEL);
        dialog.setHeaderText("Đề nghị hòa");
        boolean accept = dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        network.send(NetworkMessage.of(MessageType.DRAW_RESPONSE, JsonProtocol.object().put("accept", accept)));
    }

    private void gameOver(JsonNode data) {
        if (timer != null) timer.stop();
        String result = data.path("result").asText();
        String outcome = switch (result) {
            case "DRAW" -> "Hòa";
            case "WHITE_WIN" -> "WHITE".equals(myColor) ? "Bạn thắng" : "Bạn thua";
            default -> "BLACK".equals(myColor) ? "Bạn thắng" : "Bạn thua";
        };
        String summary = outcome + " — " + data.path("reason").asText() + eloChangeText(data);
        if (!data.path("rematchAllowed").asBoolean(true)) {
            alert(Alert.AlertType.INFORMATION, "Kết thúc ván đấu", summary);
            return;
        }
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                summary + "\nBạn có muốn tái đấu?", ButtonType.YES, ButtonType.NO);
        dialog.setHeaderText("Kết thúc ván đấu");
        boolean accept = dialog.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
        network.send(NetworkMessage.of(MessageType.REMATCH_RESPONSE, JsonProtocol.object().put("accept", accept)));
    }

    private void updateClock() {
        long remain = Math.max(0, deadline - System.currentTimeMillis());
        long totalSeconds = (remain + 999) / 1000;
        String activeTime = String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
        boolean myTurn = Objects.equals(currentTurn, myColor);
        myTimerLabel.setText(myTurn ? activeTime : "00:30");
        opponentTimerLabel.setText(myTurn ? "00:30" : activeTime);
        setClockActive(myTimerLabel, myTurn);
        setClockActive(opponentTimerLabel, !myTurn);
    }

    private static void setClockActive(Label clock, boolean active) {
        if (active && !clock.getStyleClass().contains("active-clock")) {
            clock.getStyleClass().add("active-clock");
        } else if (!active) {
            clock.getStyleClass().remove("active-clock");
        }
    }

    private void showHistory(JsonNode rows) {
        TableView<JsonNode> table = new TableView<>();
        table.getColumns().addAll(column("Mã", n -> n.path("id").asText()),
                column("Trắng", n -> n.path("whitePlayer").asText()), column("Đen", n -> n.path("blackPlayer").asText()),
                column("Kết quả", n -> n.path("result").asText()), column("Số nước", n -> n.path("moveCount").asText()),
                column("Δ ELO", n -> username.equals(n.path("whitePlayer").asText())
                        ? signed(n.path("whiteEloDelta").asInt()) : signed(n.path("blackEloDelta").asInt())),
                column("Thời gian", n -> dateFormat.format(Instant.ofEpochMilli(n.path("endedAt").asLong()))));
        List<JsonNode> items = new ArrayList<>(); rows.forEach(items::add);
        table.setItems(FXCollections.observableArrayList(items));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        Button replay = new Button("Xem lại ván đã chọn");
        replay.setOnAction(event -> {
            JsonNode row = table.getSelectionModel().getSelectedItem();
            if (row != null) network.send(NetworkMessage.of(MessageType.MATCH_DETAIL_REQUEST,
                    JsonProtocol.object().put("matchId", row.path("id").asLong())));
        });
        modal("Lịch sử ván đấu", new VBox(10, table, replay), 720, 430);
    }

    private void showReplay(JsonNode rows) {
        List<JsonNode> moves = new ArrayList<>(); rows.forEach(moves::add);
        BoardView replayBoard = new BoardView(square -> { });
        String initial = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        final int[] index = {-1};
        Label position = new Label("Vị trí ban đầu");
        Runnable render = () -> {
            String fen = index[0] < 0 ? initial : moves.get(index[0]).path("fenAfter").asText();
            replayBoard.render(fen, false);
            position.setText(index[0] < 0 ? "Vị trí ban đầu" : moves.get(index[0]).path("ply").asText() + ". " + moves.get(index[0]).path("notation").asText());
        };
        Button first = new Button("|<"), previous = new Button("<"), next = new Button(">"), last = new Button(">|");
        first.setOnAction(e -> { index[0] = -1; render.run(); });
        previous.setOnAction(e -> { index[0] = Math.max(-1, index[0] - 1); render.run(); });
        next.setOnAction(e -> { index[0] = Math.min(moves.size() - 1, index[0] + 1); render.run(); });
        last.setOnAction(e -> { index[0] = moves.size() - 1; render.run(); });
        render.run();
        VBox content = new VBox(10, replayBoard, position, new HBox(8, first, previous, next, last));
        content.setAlignment(Pos.CENTER);
        content.setStyle("-fx-background-color:#7e8da0;");
        position.setStyle("-fx-text-fill:white;-fx-font-weight:bold;");
        modal("Xem lại ván đấu", content, 560, 610);
    }

    private void showRanking(JsonNode rows) {
        TableView<JsonNode> table = new TableView<>();
        table.getColumns().addAll(column("Hạng", n -> n.path("rank").asText()), column("Người chơi", n -> n.path("username").asText()),
                column("ELO ↓", n -> n.path("elo").asText()), column("TB ELO đối thủ ↓", n -> number(n, "averageOpponentElo")),
                column("TB ply trận thắng ↑", n -> number(n, "averageWinningPlies")), column("TB ply trận thua ↓", n -> number(n, "averageLosingPlies")));
        List<JsonNode> items = new ArrayList<>(); rows.forEach(items::add);
        table.setItems(FXCollections.observableArrayList(items));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        modal("Bảng xếp hạng", table, 820, 450);
    }

    private static String number(JsonNode node, String field) {
        return node.path(field).isNull() || node.path(field).isMissingNode() ? "—" : String.format("%.2f", node.path(field).asDouble());
    }

    private String eloChangeText(JsonNode data) {
        String prefix = "WHITE".equals(myColor) ? "white" : "black";
        JsonNode delta = data.path(prefix + "EloDelta");
        JsonNode after = data.path(prefix + "EloAfter");
        if (delta.isMissingNode() || after.isMissingNode()) return "";
        return "\nELO: " + after.asInt() + " (" + signed(delta.asInt()) + ")";
    }

    private static String signed(int value) { return value >= 0 ? "+" + value : String.valueOf(value); }

    private void modal(String title, javafx.scene.Parent content, double width, double height) {
        Stage window = new Stage();
        window.initOwner(stage); window.initModality(Modality.NONE); window.setTitle(title);
        if (content instanceof Region region) region.setPadding(new Insets(12));
        window.setScene(new Scene(content, width, height)); window.show();
    }

    private static TableColumn<JsonNode, String> column(String title, java.util.function.Function<JsonNode, String> value) {
        TableColumn<JsonNode, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private static void toast(String message) { if (message != null && !message.isBlank()) alert(Alert.AlertType.INFORMATION, "Thông báo", message); }
    private static void alert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK); alert.setHeaderText(title); alert.showAndWait();
    }
    private static boolean confirm(String message) {
        return new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO)
                .showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    @Override public void stop() throws Exception { if (network != null) network.close(); }

    private record MovePair(String number, String white, String black) { }

    private static final class ObjectNodeBuilder {
        private final com.fasterxml.jackson.databind.node.ObjectNode node = JsonProtocol.object();
        ObjectNodeBuilder put(String key, String value) { node.put(key, value); return this; }
    }

    private static final class BoardView extends StackPane {
        private static final double SQUARE_SIZE = 44;
        private static final double RANK_GUTTER = 36;
        private static final double FILE_GUTTER = 32;
        private static final Map<Character, String> PIECE_PATHS = Map.ofEntries(
                Map.entry('K', "/images/wk.png"), Map.entry('Q', "/images/wq.png"),
                Map.entry('R', "/images/wr.png"), Map.entry('B', "/images/wb.png"),
                Map.entry('N', "/images/wn.png"), Map.entry('P', "/images/wp.png"),
                Map.entry('k', "/images/bk.png"), Map.entry('q', "/images/bq.png"),
                Map.entry('r', "/images/br.png"), Map.entry('b', "/images/bb.png"),
                Map.entry('n', "/images/bn.png"), Map.entry('p', "/images/bp.png"));
        private static final Map<Character, Image> PIECE_IMAGES = loadPieceImages();
        private final Map<String, Button> squares = new HashMap<>();
        private final java.util.function.Consumer<String> click;
        private String selected;
        private String fen;
        private boolean flipped;

        BoardView(java.util.function.Consumer<String> click) {
            this.click = click;
            setAlignment(Pos.CENTER);
            setPadding(new Insets(12));
            setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            setStyle("-fx-background-color:#d7ae77;"
                    + "-fx-background-radius:16;"
                    + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.45),14,0.2,0,5);");
        }

        void render(String fen, boolean flipped) {
            this.fen = fen; this.flipped = flipped; getChildren().clear(); squares.clear();
            char[][] board = parseFen(fen);
            GridPane boardGrid = new GridPane();
            boardGrid.setStyle("-fx-background-color:#16110d;-fx-border-color:#16110d;-fx-border-width:4;");
            VBox rankLabels = new VBox();
            rankLabels.setPadding(new Insets(4, 0, 4, 0));
            for (int displayRow = 0; displayRow < 8; displayRow++) {
                int row = flipped ? 7 - displayRow : displayRow;
                rankLabels.getChildren().add(coordinateLabel(String.valueOf(8 - row), RANK_GUTTER, SQUARE_SIZE));
                for (int displayCol = 0; displayCol < 8; displayCol++) {
                    int col = flipped ? 7 - displayCol : displayCol;
                    String square = String.valueOf((char)('a' + col)) + (8 - row);
                    char piece = board[row][col];
                    Button button = new Button();
                    Image pieceImage = PIECE_IMAGES.get(piece);
                    if (pieceImage != null) {
                        ImageView imageView = new ImageView(pieceImage);
                        imageView.setFitWidth(41);
                        imageView.setFitHeight(41);
                        imageView.setPreserveRatio(true);
                        imageView.setSmooth(true);
                        button.setGraphic(imageView);
                    }
                    button.setAccessibleText(square + (piece == '.' ? " trống" : " quân " + piece));
                    button.setFocusTraversable(false);
                    button.setMinSize(SQUARE_SIZE, SQUARE_SIZE);
                    button.setPrefSize(SQUARE_SIZE, SQUARE_SIZE);
                    button.setMaxSize(SQUARE_SIZE, SQUARE_SIZE);
                    boolean light = (row + col) % 2 == 0;
                    button.setStyle("-fx-background-radius:0;-fx-background-insets:0;-fx-padding:0;"
                            + "-fx-border-width:0;-fx-focus-color:transparent;-fx-faint-focus-color:transparent;"
                            + "-fx-background-color:" + (light ? "#f1ddb8" : "#ba8c68") + ";");
                    button.setOnAction(event -> click.accept(square));
                    squares.put(square, button);
                    boardGrid.add(button, displayCol, displayRow);
                }
            }

            HBox boardRow = new HBox(rankLabels, boardGrid);
            boardRow.setAlignment(Pos.TOP_LEFT);
            HBox fileLabels = new HBox();
            Region corner = new Region();
            corner.setMinWidth(RANK_GUTTER + 4);
            corner.setPrefWidth(RANK_GUTTER + 4);
            corner.setMaxWidth(RANK_GUTTER + 4);
            fileLabels.getChildren().add(corner);
            for (int displayCol = 0; displayCol < 8; displayCol++) {
                int col = flipped ? 7 - displayCol : displayCol;
                String file = String.valueOf(Character.toUpperCase((char) ('a' + col)));
                fileLabels.getChildren().add(coordinateLabel(file, SQUARE_SIZE, FILE_GUTTER));
            }

            VBox woodenFrame = new VBox(boardRow, fileLabels);
            woodenFrame.setPadding(new Insets(36, 34, 6, 12));
            woodenFrame.setStyle("-fx-background-color:#ba8959;-fx-background-radius:6;");
            getChildren().add(woodenFrame);
            selected = null;
        }

        private static Label coordinateLabel(String text, double width, double height) {
            Label label = new Label(text);
            label.setAlignment(Pos.CENTER);
            label.setMinSize(width, height);
            label.setPrefSize(width, height);
            label.setMaxSize(width, height);
            label.setStyle("-fx-text-fill:white;-fx-font-size:15px;-fx-font-weight:bold;"
                    + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.9),3,0.75,0,1);");
            return label;
        }

        private static Map<Character, Image> loadPieceImages() {
            Map<Character, Image> images = new HashMap<>();
            PIECE_PATHS.forEach((piece, path) -> {
                try (var stream = ChessClientApp.class.getResourceAsStream(path)) {
                    if (stream != null) images.put(piece, new Image(stream));
                } catch (Exception ignored) {
                    // Nếu một ảnh bị thiếu, ô cờ vẫn được hiển thị và chương trình vẫn chạy.
                }
            });
            return Collections.unmodifiableMap(images);
        }

        void select(String square) {
            clearSelection(); selected = square;
            Button button = squares.get(square);
            if (button != null) button.setStyle(button.getStyle() + "-fx-border-color:#ffcc00;-fx-border-width:4;");
        }

        void clearSelection() { if (selected != null && fen != null) render(fen, flipped); selected = null; }
        char pieceAt(String square) {
            char[][] board = parseFen(fen);
            return board[8 - Character.digit(square.charAt(1), 10)][square.charAt(0) - 'a'];
        }

        private static char[][] parseFen(String fen) {
            char[][] board = new char[8][8]; for (char[] row : board) Arrays.fill(row, '.');
            if (fen == null || fen.isBlank()) return board;
            String[] rows = fen.split(" ")[0].split("/");
            for (int row = 0; row < Math.min(8, rows.length); row++) {
                int col = 0;
                for (char token : rows[row].toCharArray()) {
                    if (Character.isDigit(token)) col += Character.digit(token, 10);
                    else if (col < 8) board[row][col++] = token;
                }
            }
            return board;
        }
    }
}
