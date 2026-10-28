package vn.edu.ltm.chess.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import vn.edu.ltm.chess.chess.ChessColor;
import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.MessageType;
import vn.edu.ltm.chess.protocol.NetworkMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class GameView {
    private static final long DEFAULT_TURN_TIME_MS = 30_000L;

    private final String username;
    private final Consumer<NetworkMessage> sender;
    private final Runnable returnToLobby;
    private final BoardView boardView;
    private final Label turnLabel = new Label();
    private final Label myTimerLabel = new Label("00:30");
    private final Label opponentTimerLabel = new Label("00:30");
    private final TableView<MovePair> moveTable = createMoveTable();
    private final ListView<String> chatList = new ListView<>();
    private final TextField chatInput = new TextField();
    private final Timeline timer;
    private final StackPane root;

    private String myColor;
    private String currentTurn;
    private String selectedSquare;
    private final Set<String> legalDestinations = new HashSet<>();
    private long remainingAtSyncMs = DEFAULT_TURN_TIME_MS;
    private long syncNanos = System.nanoTime();
    private boolean terminalPopupOpen;
    private boolean deferClosedStatus;
    private int renderedMoveCount;
    private long lastWarningSecond = 6;
    private boolean resultSoundPlayed;

    public GameView(String username, JsonNode initialState, Consumer<NetworkMessage> sender,
                    Runnable returnToLobby) {
        this.username = username;
        this.sender = sender;
        this.returnToLobby = returnToLobby;
        myColor = initialState.path("yourColor").asText();
        String whitePlayer = initialState.path("white").asText();
        String blackPlayer = initialState.path("black").asText();
        String opponent = username.equals(whitePlayer) ? blackPlayer : whitePlayer;

        boardView = new BoardView(this::squareClicked);
        turnLabel.setMaxWidth(Double.MAX_VALUE);
        turnLabel.setAlignment(Pos.CENTER);
        turnLabel.getStyleClass().add("turn-label");
        myTimerLabel.getStyleClass().add("clock-label");
        opponentTimerLabel.getStyleClass().add("clock-label");
        chatList.getStyleClass().add("game-list");
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
        draw.setOnAction(event -> send(MessageType.DRAW_OFFER, JsonProtocol.object()));
        resign.setOnAction(event -> DialogOverlay.confirm(boardView, DialogOverlay.Tone.DANGER,
                tiltedKing(45), "XÁC NHẬN ĐẦU HÀNG",
                "Bạn chắc chắn muốn đầu hàng ván đấu này?",
                "Đầu hàng", "Tiếp tục chơi",
                accept -> { if (accept) send(MessageType.RESIGN, JsonProtocol.object()); }));
        exit.setOnAction(event -> DialogOverlay.confirm(boardView, DialogOverlay.Tone.DANGER,
                DialogOverlay.unicodeIcon("⚠"), "XÁC NHẬN THOÁT TRẬN",
                "Thoát khi đang thi đấu sẽ bị xử thua và bị trừ ELO.",
                "Thoát trận", "Ở lại",
                accept -> { if (accept) send(MessageType.EXIT_GAME, JsonProtocol.object()); }));

        HBox opponentCard = playerCard(opponent,
                "WHITE".equals(myColor) ? "ĐEN" : "TRẮNG", opponentTimerLabel);
        HBox myCard = playerCard(username,
                "WHITE".equals(myColor) ? "TRẮNG" : "ĐEN", myTimerLabel);
        VBox boardColumn = new VBox(8, opponentCard, boardView, myCard);
        boardColumn.setAlignment(Pos.CENTER);
        boardColumn.setMaxWidth(Double.MAX_VALUE);

        HBox chatComposer = new HBox(8, chatInput, send);
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        VBox chatPanel = new VBox(8, panelTitle("TRÒ CHUYỆN"), chatList, chatComposer);
        chatPanel.getStyleClass().add("game-panel");
        chatPanel.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(chatList, Priority.ALWAYS);
        VBox movesPanel = new VBox(8, panelTitle("LỊCH SỬ NƯỚC ĐI"), moveTable);
        movesPanel.getStyleClass().add("game-panel");
        movesPanel.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(moveTable, Priority.ALWAYS);

        GridPane sidePanels = new GridPane();
        sidePanels.setVgap(10);
        ColumnConstraints sideColumn = new ColumnConstraints();
        sideColumn.setPercentWidth(100);
        sideColumn.setHgrow(Priority.ALWAYS);
        sideColumn.setFillWidth(true);
        RowConstraints chatRow = new RowConstraints();
        chatRow.setPercentHeight(50);
        chatRow.setVgrow(Priority.ALWAYS);
        chatRow.setFillHeight(true);
        RowConstraints movesRow = new RowConstraints();
        movesRow.setPercentHeight(50);
        movesRow.setVgrow(Priority.ALWAYS);
        movesRow.setFillHeight(true);
        sidePanels.getColumnConstraints().add(sideColumn);
        sidePanels.getRowConstraints().addAll(chatRow, movesRow);
        sidePanels.add(chatPanel, 0, 0);
        sidePanels.add(movesPanel, 0, 1);

        HBox actions = new HBox(8, draw, resign, exit);
        for (Button button : List.of(draw, resign, exit)) {
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
        }
        VBox rightColumn = new VBox(10, sidePanels, actions, turnLabel);
        rightColumn.setMinWidth(285);
        rightColumn.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(sidePanels, Priority.ALWAYS);

        GridPane gameContent = new GridPane();
        gameContent.setHgap(18);
        gameContent.setAlignment(Pos.CENTER);
        ColumnConstraints gameArea = new ColumnConstraints();
        gameArea.setPercentWidth(60);
        gameArea.setHgrow(Priority.ALWAYS);
        gameArea.setFillWidth(true);
        ColumnConstraints communicationArea = new ColumnConstraints();
        communicationArea.setPercentWidth(40);
        communicationArea.setHgrow(Priority.ALWAYS);
        communicationArea.setFillWidth(true);
        gameContent.getColumnConstraints().addAll(gameArea, communicationArea);
        gameContent.add(boardColumn, 0, 0);
        gameContent.add(rightColumn, 1, 0);
        GridPane.setHgrow(boardColumn, Priority.ALWAYS);
        GridPane.setHgrow(rightColumn, Priority.ALWAYS);
        GridPane.setVgrow(boardColumn, Priority.ALWAYS);
        GridPane.setVgrow(rightColumn, Priority.ALWAYS);
        root = new StackPane(gameContent);
        root.getStyleClass().add("game-root");

        updateState(initialState);
        timer = new Timeline(new KeyFrame(Duration.millis(100), event -> updateClock()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    public Parent root() { return root; }

    public void updateState(JsonNode data) {
        String previousTurn = currentTurn;
        currentTurn = data.path("turn").asText();
        boolean inCheck = data.path("inCheck").asBoolean(false);
        boardView.render(data.path("fen").asText(), "BLACK".equals(myColor),
                inCheck ? currentTurn : null);
        selectedSquare = null;
        legalDestinations.clear();
        turnLabel.setText("Lượt: " + ("WHITE".equals(currentTurn) ? "Trắng" : "Đen")
                + (inCheck ? " — ĐANG BỊ CHIẾU" : ""));
        if (data.path("remainingTurnTimeMs").isNumber()) {
            remainingAtSyncMs = Math.max(0, data.path("remainingTurnTimeMs").asLong());
        } else {
            remainingAtSyncMs = Math.max(0,
                    data.path("deadlineEpochMs").asLong() - System.currentTimeMillis());
        }
        syncNanos = System.nanoTime();

        List<JsonNode> moves = new ArrayList<>();
        data.path("moves").forEach(moves::add);
        playMoveSound(moves);
        List<MovePair> rows = new ArrayList<>();
        for (int index = 0; index < moves.size(); index += 2) {
            String whiteMove = notationOf(moves.get(index));
            String blackMove = index + 1 < moves.size()
                    ? notationOf(moves.get(index + 1)) : "";
            rows.add(new MovePair(String.valueOf(index / 2 + 1), whiteMove, blackMove));
        }
        moveTable.setItems(FXCollections.observableArrayList(rows));
        if (!rows.isEmpty()) moveTable.scrollTo(rows.size() - 1);
        if (!Objects.equals(previousTurn, currentTurn)) {
            lastWarningSecond = 6;
            SoundManager.stop(SoundManager.Effect.LOW_TIME);
        }
        updateClock();
    }

    public void addChatMessage(JsonNode data) {
        chatList.getItems().add(data.path("from").asText() + ": " + data.path("message").asText());
        if (!username.equals(data.path("from").asText())) {
            SoundManager.play(SoundManager.Effect.NOTIFY);
        }
    }

    public void showLegalMoves(JsonNode data) {
        String from = data.path("from").asText();
        if (!Objects.equals(from, selectedSquare)) return;
        legalDestinations.clear();
        data.path("destinations").forEach(node -> legalDestinations.add(node.asText()));
        boardView.showLegalMoves(legalDestinations);
    }

    public void handleDrawOffer(JsonNode data) {
        SoundManager.play(SoundManager.Effect.NOTIFY);
        DialogOverlay.confirm(root, DialogOverlay.Tone.DRAW,
                DialogOverlay.unicodeIcon("🤝"), "ĐỀ NGHỊ HÒA",
                data.path("from").asText() + " đề nghị kết thúc ván đấu với kết quả hòa.",
                "Đồng ý hòa", "Từ chối",
                accept -> send(MessageType.DRAW_RESPONSE,
                        JsonProtocol.object().put("accept", accept)));
    }

    public void handleGameOver(JsonNode data) {
        stop();
        String result = data.path("result").asText();
        boolean draw = "DRAW".equals(result);
        boolean win = !draw && (("WHITE_WIN".equals(result) && "WHITE".equals(myColor))
                || ("BLACK_WIN".equals(result) && "BLACK".equals(myColor)));
        if (!resultSoundPlayed) {
            SoundManager.play(draw ? SoundManager.Effect.DRAW
                    : win ? SoundManager.Effect.WIN : SoundManager.Effect.LOSE);
            resultSoundPlayed = true;
        }
        DialogOverlay.Tone tone = draw ? DialogOverlay.Tone.DRAW
                : win ? DialogOverlay.Tone.WIN : DialogOverlay.Tone.LOSE;
        boolean checkmate = "Chiếu bí".equalsIgnoreCase(data.path("reason").asText());
        String title = draw ? "VÁN ĐẤU HÒA"
                : checkmate ? (win ? "BẠN ĐÃ CHIẾU HẾT" : "BẠN ĐÃ BỊ CHIẾU HẾT")
                : win ? "BẠN ĐÃ THẮNG" : "BẠN ĐÃ THUA";
        javafx.scene.Node illustration = draw ? DialogOverlay.unicodeIcon("🤝")
                : resultKing(!win);
        String badge = eloBadgeText(data);
        String badgeClass = draw ? "elo-neutral" : win ? "elo-positive" : "elo-negative";
        String message = checkmate
                ? (win
                    ? "Đối thủ không còn nước đi hợp lệ để thoát chiếu.\n"
                            + "Bạn có thể quan sát thế chiếu hết trên bàn cờ phía sau."
                    : "Vua của bạn không còn nước đi hợp lệ để thoát chiếu.\n"
                            + "Ô Vua màu đỏ cho biết vị trí bị chiếu hết.")
                : data.path("reason").asText();
        terminalPopupOpen = true;
        boolean rematchAllowed = data.path("rematchAllowed").asBoolean(true);
        deferClosedStatus = !rematchAllowed;

        if (!rematchAllowed) {
            showResultPopup(checkmate, tone, illustration, title, message,
                    badge, badgeClass,
                    DialogOverlay.Action.secondary("Về sảnh", () -> {
                        terminalPopupOpen = false;
                        deferClosedStatus = false;
                        returnToLobby.run();
                    }));
            return;
        }
        showResultPopup(checkmate, tone, illustration, title, message,
                badge, badgeClass,
                DialogOverlay.Action.primary("Tái đấu", () -> answerRematch(true)),
                DialogOverlay.Action.secondary("Về sảnh", () -> answerRematch(false)));
    }

    private void showResultPopup(boolean boardVisible, DialogOverlay.Tone tone,
                                 javafx.scene.Node illustration, String title, String message,
                                 String badge, String badgeClass, DialogOverlay.Action... actions) {
        if (boardVisible) {
            DialogOverlay.showBoardVisible(root, tone, illustration, title, message,
                    badge, badgeClass, actions);
        } else {
            DialogOverlay.show(root, tone, illustration, title, message,
                    badge, badgeClass, actions);
        }
    }

    public boolean terminalPopupOpen() { return terminalPopupOpen; }
    public boolean shouldDeferClosedStatus() { return deferClosedStatus; }

    public void stop() {
        timer.stop();
        SoundManager.stop(SoundManager.Effect.LOW_TIME);
    }

    private void squareClicked(String square) {
        if (selectedSquare == null) {
            selectPiece(square);
            return;
        }
        if (selectedSquare.equals(square)) {
            clearSelection();
            return;
        }
        if (isMyPiece(boardView.pieceAt(square))) {
            selectPiece(square);
            return;
        }
        if (!legalDestinations.contains(square)) {
            SoundManager.play(SoundManager.Effect.ILLEGAL);
            return;
        }

        ObjectNode move = JsonProtocol.object().put("from", selectedSquare).put("to", square);
        char piece = boardView.pieceAt(selectedSquare);
        if ((piece == 'P' && square.endsWith("8")) || (piece == 'p' && square.endsWith("1"))) {
            String from = selectedSquare;
            clearSelection();
            DialogOverlay.promotion(root, ChessColor.valueOf(myColor),
                    promotion -> sendMove(from, square, promotion));
            return;
        }
        send(MessageType.MOVE, move);
        clearSelection();
    }

    private void selectPiece(String square) {
        if (!Objects.equals(currentTurn, myColor) || !isMyPiece(boardView.pieceAt(square))) {
            clearSelection();
            SoundManager.play(SoundManager.Effect.ILLEGAL);
            return;
        }
        selectedSquare = square;
        legalDestinations.clear();
        boardView.select(square);
        send(MessageType.LEGAL_MOVES_REQUEST, JsonProtocol.object().put("from", square));
    }

    private boolean isMyPiece(char piece) {
        if (piece == '.') return false;
        return "WHITE".equals(myColor) ? Character.isUpperCase(piece) : Character.isLowerCase(piece);
    }

    private void clearSelection() {
        selectedSquare = null;
        legalDestinations.clear();
        boardView.clearSelection();
    }

    private void sendMove(String from, String to, String promotion) {
        send(MessageType.MOVE, JsonProtocol.object()
                .put("from", from).put("to", to).put("promotion", promotion));
    }

    private void answerRematch(boolean accept) {
        terminalPopupOpen = false;
        deferClosedStatus = false;
        send(MessageType.REMATCH_RESPONSE, JsonProtocol.object().put("accept", accept));
    }

    private javafx.scene.Node tiltedKing(double rotation) {
        javafx.scene.Node king = DialogOverlay.pieceIcon(myColor, "k", 64);
        king.setRotate(rotation);
        return king;
    }

    private javafx.scene.Node resultKing(boolean defeated) {
        javafx.scene.Node king = DialogOverlay.pieceIcon(myColor, "k", 68);
        if (defeated) king.setRotate(90);
        else king.setEffect(new DropShadow(20, Color.web("#f5c451")));
        return king;
    }

    private void sendChat() {
        String content = chatInput.getText().trim();
        if (content.isEmpty()) return;
        send(MessageType.CHAT_SEND, JsonProtocol.object().put("message", content));
        chatInput.clear();
    }

    private void send(MessageType type, JsonNode payload) {
        sender.accept(NetworkMessage.of(type, payload));
    }

    private void updateClock() {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - syncNanos);
        long remain = Math.max(0, remainingAtSyncMs - elapsedMs);
        long totalSeconds = (remain + 999) / 1000;
        String activeTime = String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
        boolean myTurn = Objects.equals(currentTurn, myColor);
        if (myTurn && totalSeconds > 0 && totalSeconds <= 5 && totalSeconds < lastWarningSecond) {
            SoundManager.play(SoundManager.Effect.LOW_TIME);
            lastWarningSecond = totalSeconds;
        }
        myTimerLabel.setText(myTurn ? activeTime : "00:30");
        opponentTimerLabel.setText(myTurn ? "00:30" : activeTime);
        setClockActive(myTimerLabel, myTurn);
        setClockActive(opponentTimerLabel, !myTurn);
    }

    private String eloBadgeText(JsonNode data) {
        String prefix = "WHITE".equals(myColor) ? "white" : "black";
        JsonNode delta = data.path(prefix + "EloDelta");
        JsonNode after = data.path(prefix + "EloAfter");
        if (delta.isMissingNode() || after.isMissingNode()) return null;
        return "ELO: " + after.asInt() + " (" + signed(delta.asInt()) + ")";
    }

    private static String signed(int value) { return value >= 0 ? "+" + value : String.valueOf(value); }

    private static String notationOf(JsonNode move) {
        String notation = move.path("notation").asText();
        return notation.isBlank() ? move.path("to").asText() : notation;
    }

    private void playMoveSound(List<JsonNode> moves) {
        if (moves.size() < renderedMoveCount) {
            renderedMoveCount = 0;
        }
        if (moves.size() <= renderedMoveCount) {
            return;
        }
        String notation = notationOf(moves.get(moves.size() - 1));
        renderedMoveCount = moves.size();
        SoundManager.play(SoundManager.moveEffect(notation));
    }

    private static void setClockActive(Label clock, boolean active) {
        if (active && !clock.getStyleClass().contains("active-clock")) {
            clock.getStyleClass().add("active-clock");
        } else if (!active) {
            clock.getStyleClass().remove("active-clock");
        }
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

    private static TableView<MovePair> createMoveTable() {
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

    private record MovePair(String number, String white, String black) { }
}
