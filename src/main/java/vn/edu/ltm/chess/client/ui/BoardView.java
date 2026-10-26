package vn.edu.ltm.chess.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;

import java.util.*;
import java.util.function.Consumer;

final class BoardView extends StackPane {
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
    private final Map<String, StackPane> squareLayers = new HashMap<>();
    private final List<Circle> legalMoveDots = new ArrayList<>();
    private final List<Region> replayHighlights = new ArrayList<>();
    private final Consumer<String> click;
    private String selected;
    private String fen;
    private boolean flipped;
    private String checkedColor;

    BoardView(Consumer<String> click) {
        this.click = click;
        setAlignment(Pos.CENTER);
        setPadding(new Insets(12));
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setStyle("-fx-background-color:#d4b483;"
                + "-fx-background-radius:16;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.45),14,0.2,0,5);");
    }

    void render(String fen, boolean flipped) {
        render(fen, flipped, null);
    }

    void render(String fen, boolean flipped, String checkedColor) {
        this.fen = fen;
        this.flipped = flipped;
        this.checkedColor = checkedColor;
        getChildren().clear();
        squares.clear();
        squareLayers.clear();
        legalMoveDots.clear();
        replayHighlights.clear();
        char[][] board = parseFen(fen);
        GridPane boardGrid = new GridPane();
        boardGrid.setStyle("-fx-background-color:#1a120d;-fx-border-color:#1a120d;-fx-border-width:4;");
        VBox rankLabels = new VBox();
        rankLabels.setPadding(new Insets(4, 0, 4, 0));
        for (int displayRow = 0; displayRow < 8; displayRow++) {
            int row = flipped ? 7 - displayRow : displayRow;
            rankLabels.getChildren().add(coordinateLabel(String.valueOf(8 - row), RANK_GUTTER, SQUARE_SIZE));
            for (int displayCol = 0; displayCol < 8; displayCol++) {
                int col = flipped ? 7 - displayCol : displayCol;
                String square = String.valueOf((char) ('a' + col)) + (8 - row);
                char piece = board[row][col];
                Button button = new Button();
                StackPane squareLayer = new StackPane();
                squareLayer.setMouseTransparent(true);
                squareLayer.setMinSize(SQUARE_SIZE, SQUARE_SIZE);
                squareLayer.setPrefSize(SQUARE_SIZE, SQUARE_SIZE);
                squareLayer.setMaxSize(SQUARE_SIZE, SQUARE_SIZE);
                Image pieceImage = PIECE_IMAGES.get(piece);
                if (pieceImage != null) {
                    ImageView imageView = new ImageView(pieceImage);
                    imageView.setFitWidth(41);
                    imageView.setFitHeight(41);
                    imageView.setPreserveRatio(true);
                    imageView.setSmooth(true);
                    squareLayer.getChildren().add(imageView);
                }
                button.setGraphic(squareLayer);
                button.setAccessibleText(square + (piece == '.' ? " trống" : " quân " + piece));
                button.setFocusTraversable(false);
                button.setMinSize(SQUARE_SIZE, SQUARE_SIZE);
                button.setPrefSize(SQUARE_SIZE, SQUARE_SIZE);
                button.setMaxSize(SQUARE_SIZE, SQUARE_SIZE);
                boolean light = (row + col) % 2 == 0;
                button.setStyle("-fx-background-radius:0;-fx-background-insets:0;-fx-padding:0;"
                        + "-fx-border-width:0;-fx-focus-color:transparent;-fx-faint-focus-color:transparent;"
                        + "-fx-background-color:" + (light ? "#f0d9b5" : "#b58863") + ";");
                boolean checkedKing = (piece == 'K' && "WHITE".equals(checkedColor))
                        || (piece == 'k' && "BLACK".equals(checkedColor));
                if (checkedKing) {
                    button.setStyle(button.getStyle()
                            + "-fx-background-color:#dc2626;"
                            + "-fx-border-color:#fca5a5;-fx-border-width:3;"
                            + "-fx-effect:innershadow(gaussian,rgba(91,10,18,0.9),14,0.65,0,0);");
                    button.setAccessibleText(button.getAccessibleText() + " đang bị chiếu");
                }
                button.setOnAction(event -> click.accept(square));
                squares.put(square, button);
                squareLayers.put(square, squareLayer);
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
        woodenFrame.setStyle("-fx-background-color:#b58556;-fx-background-radius:6;");
        getChildren().add(woodenFrame);
        selected = null;
    }

    void select(String square) {
        clearSelection();
        selected = square;
        Button button = squares.get(square);
        if (button != null) {
            button.setStyle(button.getStyle() + "-fx-border-color:#cf7317;-fx-border-width:4;");
        }
    }

    void clearSelection() {
        if (selected != null && fen != null) render(fen, flipped, checkedColor);
        selected = null;
    }

    void showLegalMoves(Collection<String> destinations) {
        clearLegalMoves();
        for (String square : destinations) {
            StackPane layer = squareLayers.get(square);
            if (layer == null) continue;
            Circle dot = new Circle(6);
            dot.getStyleClass().add("legal-move-dot");
            dot.setMouseTransparent(true);
            layer.getChildren().add(dot);
            legalMoveDots.add(dot);
        }
    }

    void clearLegalMoves() {
        for (Circle dot : legalMoveDots) {
            if (dot.getParent() instanceof StackPane layer) layer.getChildren().remove(dot);
        }
        legalMoveDots.clear();
    }

    void highlightMove(String from, String to) {
        clearMoveHighlight();
        addReplayHighlight(from, "replay-from-highlight");
        if (!Objects.equals(from, to)) addReplayHighlight(to, "replay-to-highlight");
    }

    void clearMoveHighlight() {
        for (Region highlight : replayHighlights) {
            if (highlight.getParent() instanceof StackPane layer) {
                layer.getChildren().remove(highlight);
            }
        }
        replayHighlights.clear();
    }

    char pieceAt(String square) {
        char[][] board = parseFen(fen);
        return board[8 - Character.digit(square.charAt(1), 10)][square.charAt(0) - 'a'];
    }

    private void addReplayHighlight(String square, String styleClass) {
        StackPane layer = squareLayers.get(square);
        if (layer == null) return;
        Region highlight = new Region();
        highlight.setMouseTransparent(true);
        highlight.setMinSize(SQUARE_SIZE, SQUARE_SIZE);
        highlight.setPrefSize(SQUARE_SIZE, SQUARE_SIZE);
        highlight.setMaxSize(SQUARE_SIZE, SQUARE_SIZE);
        highlight.getStyleClass().add(styleClass);
        layer.getChildren().add(0, highlight);
        replayHighlights.add(highlight);
    }

    private static Label coordinateLabel(String text, double width, double height) {
        Label label = new Label(text);
        label.setAlignment(Pos.CENTER);
        label.setMinSize(width, height);
        label.setPrefSize(width, height);
        label.setMaxSize(width, height);
        label.setStyle("-fx-text-fill:white;-fx-font-size:13px;-fx-font-weight:900;"
                + "-fx-effect:dropshadow(gaussian,rgba(0,0,0,0.9),3,0.75,0,1);");
        return label;
    }

    private static Map<Character, Image> loadPieceImages() {
        Map<Character, Image> images = new HashMap<>();
        PIECE_PATHS.forEach((piece, path) -> {
            try (var stream = BoardView.class.getResourceAsStream(path)) {
                if (stream != null) images.put(piece, new Image(stream));
            } catch (Exception ignored) {
                // Thiếu ảnh không làm hỏng toàn bộ bàn cờ.
            }
        });
        return Collections.unmodifiableMap(images);
    }

    private static char[][] parseFen(String fen) {
        char[][] board = new char[8][8];
        for (char[] row : board) Arrays.fill(row, '.');
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
