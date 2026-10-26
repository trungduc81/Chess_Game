package vn.edu.ltm.chess.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Hồ sơ, thống kê và lịch sử của kỳ thủ đang đăng nhập. */
public final class ProfileView {
    private static final int PAGE_SIZE = 5;
    private static final String FILTER_ALL = "ALL";

    private final String username;
    private final int currentElo;
    private final Consumer<JsonNode> replay;
    private final List<JsonNode> allMatches = new ArrayList<>();
    private final List<JsonNode> filteredMatches = new ArrayList<>();
    private final Map<Long, Integer> eloAfterByMatch = new HashMap<>();
    private final ObservableList<JsonNode> pageRows = FXCollections.observableArrayList();
    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    private final TableView<JsonNode> historyTable = new TableView<>(pageRows);
    private final Label rangeLabel = new Label();
    private final Label pageLabel = new Label();
    private final Button previousPage = new Button("« Trước");
    private final Button nextPage = new Button("Sau »");
    private final StackPane root;
    private int currentPage = 1;

    public ProfileView(String username, int currentElo, JsonNode rows, Runnable back,
            Consumer<JsonNode> replay) {
        this.username = username;
        this.currentElo = currentElo;
        this.replay = replay;
        rows.forEach(allMatches::add);
        deriveEloAfterValues();

        HBox header = createHeader(back);
        MatchStats stats = calculateStats();
        HBox dashboard = createDashboard(stats);
        VBox history = createHistorySection();

        VBox card = new VBox(14, header, dashboard, history);
        card.getStyleClass().addAll("records-card", "profile-card");
        card.setMaxSize(1180, 800);
        VBox.setVgrow(history, Priority.ALWAYS);

        root = UiSupport.photoRoot("/images/login_background.png", card, 0.10, 18);
        root.getStyleClass().addAll("records-root", "profile-root");
        applyFilter(FILTER_ALL);
    }

    public Parent root() {
        return root;
    }

    private HBox createHeader(Runnable back) {
        Label title = new Label("👤 HỒ SƠ KỲ THỦ");
        title.getStyleClass().add("screen-title");
        Label player = new Label(username);
        player.getStyleClass().add("profile-header-name");
        HBox left = new HBox(12, title, player);
        left.setAlignment(Pos.CENTER_LEFT);

        Button backButton = new Button("Quay lại sảnh");
        backButton.getStyleClass().add("btn-secondary");
        backButton.setOnAction(event -> back.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(14, left, spacer, backButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("records-header");
        return header;
    }

    private HBox createDashboard(MatchStats stats) {
        VBox identity = statCard();
        Label identityTitle = new Label("THÔNG TIN KỲ THỦ");
        identityTitle.getStyleClass().add("stat-card-title");
        javafx.scene.Node crown = DialogOverlay.pieceIcon("WHITE", "k", 42);
        Label name = new Label(username);
        name.getStyleClass().add("profile-name");
        Label elo = new Label("ELO  " + currentElo);
        elo.getStyleClass().addAll("profile-elo", "mono-value");
        VBox identityContent = centeredContent(crown, name, elo);
        identity.getChildren().addAll(identityTitle, identityContent);

        VBox summary = statCard();
        Label summaryTitle = new Label("CHỈ SỐ TRẬN ĐẤU");
        summaryTitle.getStyleClass().add("stat-card-title");
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(14);
        grid.setAlignment(Pos.CENTER);
        addMetric(grid, 0, 0, "Số trận", stats.total(), "stat-total");
        addMetric(grid, 1, 0, "Thắng", stats.wins(), "stat-win");
        addMetric(grid, 0, 1, "Hòa", stats.draws(), "stat-draw");
        addMetric(grid, 1, 1, "Thua", stats.losses(), "stat-loss");
        VBox summaryContent = centeredContent(grid);
        summary.getChildren().addAll(summaryTitle, summaryContent);

        VBox chartCard = statCard();
        Label chartTitle = new Label("TỶ LỆ KẾT QUẢ");
        chartTitle.getStyleClass().add("stat-card-title");
        Canvas chart = donutChart(stats);
        VBox legend = new VBox(7,
                legend("●  Thắng", "legend-win"),
                legend("●  Hòa", "legend-draw"),
                legend("●  Thua", "legend-loss"));
        HBox chartBody = new HBox(18, chart, legend);
        chartBody.setAlignment(Pos.CENTER);
        chartBody.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(chartBody, Priority.ALWAYS);
        chartCard.getChildren().addAll(chartTitle, chartBody);

        HBox dashboard = new HBox(14, identity, summary, chartCard);
        dashboard.setAlignment(Pos.CENTER);
        for (VBox card : List.of(identity, summary, chartCard)) {
            HBox.setHgrow(card, Priority.ALWAYS);
        }
        return dashboard;
    }

    private VBox createHistorySection() {
        Label title = new Label("📜 LỊCH SỬ VÁN ĐẤU");
        title.getStyleClass().add("section-title");
        ToggleGroup filterGroup = new ToggleGroup();
        HBox filters = new HBox(7,
                filterButton("Tất cả", FILTER_ALL, filterGroup, true),
                filterButton("Thắng", "WIN", filterGroup, false),
                filterButton("Hòa", "DRAW", filterGroup, false),
                filterButton("Thua", "LOSS", filterGroup, false));
        filters.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox sectionHeader = new HBox(12, title, spacer, filters);
        sectionHeader.setAlignment(Pos.CENTER_LEFT);

        configureHistoryTable();
        previousPage.getStyleClass().addAll("btn-secondary", "small-button");
        nextPage.getStyleClass().addAll("btn-secondary", "small-button");
        previousPage.setOnAction(event -> {
            currentPage--;
            updatePage();
        });
        nextPage.setOnAction(event -> {
            currentPage++;
            updatePage();
        });
        rangeLabel.getStyleClass().add("pagination-info");
        pageLabel.getStyleClass().addAll("pagination-page", "mono-value");
        Region pageSpacer = new Region();
        HBox.setHgrow(pageSpacer, Priority.ALWAYS);
        HBox controls = new HBox(9, rangeLabel, pageSpacer, previousPage, pageLabel, nextPage);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("pagination-bar");

        VBox section = new VBox(9, sectionHeader, historyTable, controls);
        section.getStyleClass().add("profile-history");
        VBox.setVgrow(historyTable, Priority.ALWAYS);
        return section;
    }

    private void configureHistoryTable() {
        TableColumn<JsonNode, String> time = UiSupport.column("Thời gian", node -> {
            long endedAt = node.path("endedAt").asLong(0);
            return endedAt > 0 ? dateFormat.format(Instant.ofEpochMilli(endedAt)) : "—";
        });
        TableColumn<JsonNode, String> opponent = UiSupport.column("Đối thủ", node ->
                playedWhite(node) ? node.path("blackPlayer").asText() : node.path("whitePlayer").asText());
        TableColumn<JsonNode, String> color = UiSupport.column("Màu quân", node ->
                playedWhite(node) ? "Trắng" : "Đen");
        color.getStyleClass().add("col-center");

        TableColumn<JsonNode, JsonNode> result = new TableColumn<>("Kết quả");
        result.getStyleClass().add("col-center");
        result.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        result.setCellFactory(column -> new TableCell<>() {
            private final Label badge = new Label();
            {
                setAlignment(Pos.CENTER);
                badge.setMaxWidth(Double.MAX_VALUE);
                badge.maxWidthProperty().bind(widthProperty().subtract(8));
                badge.setAlignment(Pos.CENTER);
                badge.setTextOverrun(OverrunStyle.ELLIPSIS);
            }
            @Override protected void updateItem(JsonNode match, boolean empty) {
                super.updateItem(match, empty);
                if (empty || match == null) {
                    setGraphic(null);
                    return;
                }
                String outcome = outcome(match);
                String reason = displayReason(match.path("reason").asText(""));
                String text = switch (outcome) {
                    case "WIN" -> "● Thắng";
                    case "DRAW" -> "● Hòa";
                    default -> "● Thua";
                };
                if (!reason.isEmpty()) text += " (" + reason + ")";
                badge.setText(text);
                badge.getStyleClass().setAll("status-badge",
                        "WIN".equals(outcome) ? "status-ready"
                                : "DRAW".equals(outcome) ? "status-draw" : "status-lose");
                setGraphic(badge);
                setText(null);
            }
        });

        TableColumn<JsonNode, JsonNode> elo = new TableColumn<>("Biến động ELO");
        elo.getStyleClass().addAll("col-center", "numeric-column");
        elo.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        elo.setCellFactory(column -> new TableCell<>() {
            private final Label value = new Label();
            { setAlignment(Pos.CENTER); }
            @Override protected void updateItem(JsonNode match, boolean empty) {
                super.updateItem(match, empty);
                if (empty || match == null) {
                    setGraphic(null);
                    return;
                }
                int delta = eloDelta(match);
                int eloAfter = eloAfterByMatch.getOrDefault(match.path("id").asLong(), currentElo);
                value.setText(eloAfter + " (" + signed(delta) + ")");
                value.getStyleClass().setAll("elo-history-value", "mono-value",
                        delta >= 0 ? "elo-positive" : "elo-negative");
                setGraphic(value);
                setText(null);
            }
        });

        TableColumn<JsonNode, JsonNode> action = new TableColumn<>("Hành động");
        action.getStyleClass().add("col-center");
        action.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        action.setCellFactory(column -> new TableCell<>() {
            private final Button button = new Button("▶ Xem lại");
            {
                setAlignment(Pos.CENTER);
                button.getStyleClass().addAll("btn-primary", "small-button");
                button.setOnAction(event -> {
                    JsonNode match = getItem();
                    if (match != null) replay.accept(match);
                });
            }
            @Override protected void updateItem(JsonNode match, boolean empty) {
                super.updateItem(match, empty);
                setGraphic(empty || match == null ? null : button);
                setText(null);
            }
        });

        for (TableColumn<JsonNode, ?> column : List.of(time, opponent, color, result, elo, action)) {
            column.setSortable(false);
        }
        setColumnWidth(time, 135, 145, 155);
        setColumnWidth(opponent, 140, 155, 190);
        setColumnWidth(color, 85, 95, 110);
        result.setMinWidth(280);
        result.setPrefWidth(350);
        setColumnWidth(elo, 125, 140, 155);
        setColumnWidth(action, 100, 115, 130);
        historyTable.getColumns().setAll(time, opponent, color, result, elo, action);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        historyTable.getStyleClass().addAll("dark-table", "profile-history-table");
        historyTable.setFixedCellSize(40);
        historyTable.setMinHeight(244);
        historyTable.setPrefHeight(244);
        historyTable.setMaxHeight(244);
        Label placeholder = new Label("Chưa có ván đấu phù hợp với bộ lọc.");
        placeholder.getStyleClass().add("empty-player-text");
        historyTable.setPlaceholder(placeholder);
    }

    private ToggleButton filterButton(String label, String filter, ToggleGroup group, boolean selected) {
        ToggleButton button = new ToggleButton(label);
        button.setToggleGroup(group);
        button.setSelected(selected);
        button.getStyleClass().add("profile-filter");
        button.setOnAction(event -> {
            if (!button.isSelected()) {
                button.setSelected(true);
                return;
            }
            applyFilter(filter);
        });
        return button;
    }

    private void applyFilter(String filter) {
        filteredMatches.clear();
        for (JsonNode match : allMatches) {
            if (FILTER_ALL.equals(filter) || filter.equals(outcome(match))) filteredMatches.add(match);
        }
        currentPage = 1;
        updatePage();
    }

    private void updatePage() {
        int total = filteredMatches.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        currentPage = Math.max(1, Math.min(currentPage, totalPages));
        int from = (currentPage - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, total);
        pageRows.setAll(filteredMatches.subList(from, to));
        rangeLabel.setText(total == 0 ? "Hiển thị 0–0 trong tổng số 0 ván đấu"
                : "Hiển thị " + (from + 1) + "–" + to + " trong tổng số " + total + " ván đấu");
        pageLabel.setText("Trang " + currentPage + " / " + totalPages);
        previousPage.setDisable(currentPage == 1);
        nextPage.setDisable(currentPage == totalPages);
    }

    private MatchStats calculateStats() {
        int wins = 0;
        int draws = 0;
        int losses = 0;
        for (JsonNode match : allMatches) {
            switch (outcome(match)) {
                case "WIN" -> wins++;
                case "DRAW" -> draws++;
                default -> losses++;
            }
        }
        int total = allMatches.size();
        double winRate = total == 0 ? 0.0 : wins * 100.0 / total;
        return new MatchStats(total, wins, draws, losses, winRate);
    }

    private void deriveEloAfterValues() {
        int runningElo = currentElo;
        for (JsonNode match : allMatches) {
            eloAfterByMatch.put(match.path("id").asLong(), runningElo);
            runningElo -= eloDelta(match);
        }
    }

    private boolean playedWhite(JsonNode match) {
        return username.equals(match.path("whitePlayer").asText());
    }

    private int eloDelta(JsonNode match) {
        return playedWhite(match) ? match.path("whiteEloDelta").asInt()
                : match.path("blackEloDelta").asInt();
    }

    private String outcome(JsonNode match) {
        String result = match.path("result").asText();
        if ("DRAW".equals(result)) return "DRAW";
        boolean won = (playedWhite(match) && "WHITE_WIN".equals(result))
                || (!playedWhite(match) && "BLACK_WIN".equals(result));
        return won ? "WIN" : "LOSS";
    }

    private static VBox statCard() {
        VBox card = new VBox(8);
        card.getStyleClass().add("stat-card");
        card.setAlignment(Pos.TOP_CENTER);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static VBox centeredContent(javafx.scene.Node... children) {
        VBox content = new VBox(6, children);
        content.setAlignment(Pos.CENTER);
        content.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(content, Priority.ALWAYS);
        return content;
    }

    private static void addMetric(GridPane grid, int column, int row, String label, int value,
            String styleClass) {
        grid.add(metric(label, String.valueOf(value), styleClass), column, row);
    }

    private static VBox metric(String label, String value, String styleClass) {
        Label number = new Label(value);
        number.getStyleClass().addAll("stat-value", "mono-value", styleClass);
        Label caption = new Label(label);
        caption.getStyleClass().add("stat-label");
        VBox box = new VBox(1, number, caption);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static Label legend(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("chart-legend", styleClass);
        return label;
    }

    private static Canvas donutChart(MatchStats stats) {
        Canvas canvas = new Canvas(138, 138);
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setLineWidth(16);
        graphics.setLineCap(StrokeLineCap.BUTT);
        graphics.setStroke(Color.web("#334155"));
        graphics.strokeOval(17, 17, 104, 104);
        if (stats.total() > 0) {
            double winAngle = stats.wins() * 360.0 / stats.total();
            double drawAngle = stats.draws() * 360.0 / stats.total();
            double lossAngle = 360.0 - winAngle - drawAngle;
            double start = 90;
            start = drawArc(graphics, start, winAngle, "#22C55E");
            start = drawArc(graphics, start, drawAngle, "#38BDF8");
            drawArc(graphics, start, lossAngle, "#EF4444");
        }
        graphics.setTextAlign(javafx.scene.text.TextAlignment.CENTER);
        graphics.setTextBaseline(javafx.geometry.VPos.CENTER);
        graphics.setFill(Color.web(stats.total() == 0 ? "#F8FAFC" : "#FBBF24"));
        graphics.setFont(Font.font("Consolas", FontWeight.BOLD, 20));
        graphics.fillText(String.format(Locale.ROOT, "%.1f%%", stats.winRate()), 69, 61);
        graphics.setFill(Color.web("#94A3B8"));
        graphics.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        graphics.fillText(stats.total() == 0 ? "CHƯA CÓ TRẬN" : "TỶ LỆ THẮNG", 69, 82);
        return canvas;
    }

    private static double drawArc(GraphicsContext graphics, double start, double extent, String color) {
        if (extent <= 0) return start;
        graphics.setStroke(Color.web(color));
        graphics.strokeArc(17, 17, 104, 104, start, -extent, ArcType.OPEN);
        return start - extent;
    }

    private static String signed(int value) {
        return value >= 0 ? "+" + value : String.valueOf(value);
    }

    private static String displayReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("đầu hàng")) return "Đầu hàng";
        if (normalized.contains("hết 30 giây") || normalized.contains("hết giờ")) return "Hết giờ";
        if (normalized.contains("thoát trận")) return "Thoát trận";
        if (normalized.contains("mất kết nối")) return "Mất kết nối";
        if (normalized.contains("đồng ý hòa") || normalized.contains("thỏa thuận")) return "Thỏa thuận";
        return value;
    }

    private static void setColumnWidth(TableColumn<?, ?> column, double min, double pref, double max) {
        column.setMinWidth(min);
        column.setPrefWidth(pref);
        column.setMaxWidth(max);
    }

    private record MatchStats(int total, int wins, int draws, int losses, double winRate) { }
}
