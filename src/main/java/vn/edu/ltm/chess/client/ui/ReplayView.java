package vn.edu.ltm.chess.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/** Màn hình xem lại một ván đấu đã lưu. */
public final class ReplayView {
    private static final String STANDARD_INITIAL_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private final List<JsonNode> moves = new ArrayList<>();
    private final List<String> sanMoves = new ArrayList<>();
    private final BoardView board = new BoardView(square -> { });
    private final TableView<MovePair> moveTable = new TableView<>();
    private final Label positionLabel = new Label("Vị trí ban đầu");
    private final Button first = controlButton("⏮ Đầu");
    private final Button previous = controlButton("◀ Lùi");
    private final Button autoplayButton = new Button("▶ Tự chạy");
    private final Button next = controlButton("▶ Tiến");
    private final Button last = controlButton("⏭ Cuối");
    private final Timeline autoplay;
    private final String initialFen;
    private final StackPane root;
    private int index = -1;

    public ReplayView(JsonNode match, JsonNode moveRows, String pgn, Runnable backToProfile) {
        this(match, moveRows, pgn, "Quay lại Hồ sơ", backToProfile);
    }

    public ReplayView(JsonNode match, JsonNode moveRows, String pgn,
                      String backButtonText, Runnable backAction) {
        moveRows.forEach(moves::add);
        sanMoves.addAll(parsePgnMoves(pgn));
        if (sanMoves.size() != moves.size()) {
            sanMoves.clear();
            moves.forEach(move -> sanMoves.add(notationOf(move)));
        }
        initialFen = match.path("initialFen").asText(STANDARD_INITIAL_FEN);

        Label title = new Label("XEM LẠI VÁN ĐẤU");
        title.getStyleClass().add("records-title");
        Label players = new Label(match.path("whitePlayer").asText()
                + "  vs  " + match.path("blackPlayer").asText());
        players.getStyleClass().add("replay-players");
        Label result = new Label(localizedResult(match.path("result").asText())
                + "  •  " + match.path("reason").asText("Không có lý do kết thúc"));
        result.getStyleClass().add("replay-result");
        VBox matchInfo = new VBox(3, title, players, result);

        Button back = new Button(backButtonText);
        back.getStyleClass().add("btn-secondary");
        back.setOnAction(event -> {
            stop();
            backAction.run();
        });
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(16, matchInfo, headerSpacer, back);
        header.setAlignment(Pos.CENTER_LEFT);

        configureMoveTable();
        positionLabel.getStyleClass().add("replay-position");

        autoplayButton.getStyleClass().add("btn-primary");
        HBox controls = new HBox(8, first, previous, autoplayButton, next, last);
        controls.setAlignment(Pos.CENTER);
        controls.getStyleClass().add("replay-controls");

        first.setOnAction(event -> showManually(-1));
        previous.setOnAction(event -> showManually(Math.max(-1, index - 1)));
        next.setOnAction(event -> showManually(Math.min(moves.size() - 1, index + 1)));
        last.setOnAction(event -> showManually(moves.isEmpty() ? -1 : moves.size() - 1));

        autoplay = new Timeline(new KeyFrame(Duration.seconds(1), event -> advanceAutoplay()));
        autoplay.setCycleCount(Timeline.INDEFINITE);
        autoplayButton.setOnAction(event -> toggleAutoplay());

        VBox movePanel = new VBox(10, new Label("DANH SÁCH NƯỚC ĐI"), moveTable,
                positionLabel, controls);
        movePanel.getStyleClass().add("replay-side-panel");
        VBox.setVgrow(moveTable, Priority.ALWAYS);
        movePanel.setPrefWidth(420);

        HBox content = new HBox(18, board, movePanel);
        content.setAlignment(Pos.CENTER);
        HBox.setHgrow(movePanel, Priority.ALWAYS);

        VBox card = new VBox(16, header, content);
        card.getStyleClass().add("records-card");
        card.setMaxSize(1120, 740);
        VBox.setVgrow(content, Priority.ALWAYS);
        root = UiSupport.photoRoot("/images/login_background.png", card, 0.10, 18);
        root.getStyleClass().addAll("records-root", "replay-root");

        showPosition(-1, false);
    }

    public Parent root() { return root; }

    public void stop() {
        autoplay.stop();
        autoplayButton.setText("▶ Tự chạy");
    }

    private void configureMoveTable() {
        TableColumn<MovePair, String> number = new TableColumn<>("#");
        TableColumn<MovePair, String> white = new TableColumn<>("TRẮNG");
        TableColumn<MovePair, String> black = new TableColumn<>("ĐEN");

        number.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().number()));
        white.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().white()));
        black.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().black()));
        white.setCellFactory(column -> createMoveCell(true));
        black.setCellFactory(column -> createMoveCell(false));

        number.setMaxWidth(48);
        number.setMinWidth(42);
        moveTable.getColumns().setAll(number, white, black);
        moveTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        moveTable.setPlaceholder(new Label("Ván đấu không có nước đi đã lưu."));
        moveTable.getStyleClass().addAll("moves-table", "replay-move-table");
        moveTable.setSelectionModel(null);

        List<MovePair> rows = new ArrayList<>();
        for (int moveIndex = 0; moveIndex < moves.size(); moveIndex += 2) {
            String whiteMove = sanMoves.get(moveIndex);
            String blackMove = moveIndex + 1 < moves.size()
                    ? sanMoves.get(moveIndex + 1) : "";
            rows.add(new MovePair(String.valueOf(moveIndex / 2 + 1), whiteMove, blackMove));
        }
        moveTable.setItems(FXCollections.observableArrayList(rows));
    }

    private TableCell<MovePair, String> createMoveCell(boolean whiteMove) {
        return new TableCell<>() {
            {
                getStyleClass().add("replay-move-cell");
                setOnMouseClicked(event -> {
                    int target = getIndex() * 2 + (whiteMove ? 0 : 1);
                    if (!isEmpty() && target >= 0 && target < moves.size()) {
                        showManually(target);
                    }
                });
            }

            @Override
            protected void updateItem(String move, boolean empty) {
                super.updateItem(move, empty);
                setText(empty || move == null || move.isBlank() ? null : move);
                getStyleClass().remove("replay-current-move-cell");
                int target = getIndex() * 2 + (whiteMove ? 0 : 1);
                if (!empty && move != null && !move.isBlank() && target == ReplayView.this.index) {
                    getStyleClass().add("replay-current-move-cell");
                }
            }
        };
    }

    private void showManually(int target) {
        stop();
        showPosition(target, true);
    }

    private void showPosition(int target, boolean playSound) {
        int previousIndex = index;
        index = Math.max(-1, Math.min(target, moves.size() - 1));
        if (index < 0) {
            board.render(initialFen, false);
            board.clearMoveHighlight();
            positionLabel.setText("Vị trí ban đầu");
        } else {
            JsonNode move = moves.get(index);
            board.render(move.path("fenAfter").asText(initialFen), false);
            board.highlightMove(move.path("from").asText(), move.path("to").asText());
            positionLabel.setText("Nước " + move.path("ply").asText()
                    + ": " + sanMoves.get(index));
            moveTable.scrollTo(index / 2);
        }
        if (playSound && index != previousIndex) {
            SoundManager.play(index < 0
                    ? SoundManager.Effect.MOVE
                    : SoundManager.moveEffect(sanMoves.get(index)));
        }
        moveTable.refresh();
        updateControls();
    }

    private void toggleAutoplay() {
        if (autoplay.getStatus() == Animation.Status.RUNNING) {
            stop();
            return;
        }
        if (moves.isEmpty()) return;
        if (index >= moves.size() - 1) showPosition(-1, false);
        autoplayButton.setText("⏸ Dừng");
        autoplay.playFromStart();
    }

    private void advanceAutoplay() {
        if (index < moves.size() - 1) {
            showPosition(index + 1, true);
        } else {
            stop();
        }
    }

    private void updateControls() {
        boolean atStart = index < 0;
        boolean atEnd = moves.isEmpty() || index >= moves.size() - 1;
        first.setDisable(atStart);
        previous.setDisable(atStart);
        next.setDisable(atEnd);
        last.setDisable(atEnd);
        autoplayButton.setDisable(moves.isEmpty());
    }

    private static Button controlButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("btn-secondary");
        return button;
    }

    private static String localizedResult(String result) {
        return switch (result) {
            case "WHITE_WIN" -> "Trắng thắng";
            case "BLACK_WIN" -> "Đen thắng";
            case "DRAW" -> "Hòa";
            default -> result;
        };
    }

    private static String notationOf(JsonNode move) {
        String notation = move.path("notation").asText();
        return notation.isBlank() ? move.path("to").asText() : notation;
    }

    private static List<String> parsePgnMoves(String pgn) {
        if (pgn == null || pgn.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : pgn.trim().split("\\s+")) {
            if (token.matches("\\d+\\.(\\.\\.)?")
                    || token.equals("1-0") || token.equals("0-1")
                    || token.equals("1/2-1/2") || token.equals("*")) {
                continue;
            }
            result.add(token);
        }
        return result;
    }

    private record MovePair(String number, String white, String black) { }
}
