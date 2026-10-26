package vn.edu.ltm.chess.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import vn.edu.ltm.chess.protocol.JsonProtocol;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Màn hình bảng xếp hạng kỳ thủ. */
public final class RankingView {
    private enum Direction {
        ASC("Tăng dần"),
        DESC("Giảm dần");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final String username;
    private final ObservableList<JsonNode> rankings = FXCollections.observableArrayList();
    private final FilteredList<JsonNode> filteredRankings = new FilteredList<>(rankings);
    private final Label selfRankValue = new Label();
    private final StackPane root;

    public RankingView(String username, JsonNode rows, Runnable back,
                       Consumer<JsonNode> requestRanking) {
        this.username = username;

        Label title = new Label("🏆 BẢNG XẾP HẠNG KỲ THỦ");
        title.getStyleClass().add("screen-title");

        CheckBox eloEnabled = criterionCheckBox("ELO");
        CheckBox matchesEnabled = criterionCheckBox("Số trận");
        CheckBox rateEnabled = criterionCheckBox("Tỷ lệ thắng");
        ComboBox<Direction> eloDirection = directionSelector();
        ComboBox<Direction> matchesDirection = directionSelector();
        ComboBox<Direction> rateDirection = directionSelector();
        eloDirection.disableProperty().bind(eloEnabled.selectedProperty().not());
        matchesDirection.disableProperty().bind(matchesEnabled.selectedProperty().not());
        rateDirection.disableProperty().bind(rateEnabled.selectedProperty().not());

        Label filterHint = new Label("Ưu tiên từ trên xuống");
        filterHint.getStyleClass().add("ranking-filter-hint");
        Button applyFilter = new Button("Lọc");
        applyFilter.getStyleClass().add("ranking-filter-apply");
        Button defaultFilter = new Button("Mặc định");
        defaultFilter.getStyleClass().add("ranking-filter-default");
        applyFilter.disableProperty().bind(eloEnabled.selectedProperty()
                .or(matchesEnabled.selectedProperty()).or(rateEnabled.selectedProperty()).not());

        HBox filterActions = new HBox(8, applyFilter, defaultFilter);
        filterActions.setAlignment(Pos.CENTER_RIGHT);
        VBox filterPanel = new VBox(9, filterHint,
                criterionRow(eloEnabled, eloDirection),
                criterionRow(matchesEnabled, matchesDirection),
                criterionRow(rateEnabled, rateDirection), filterActions);
        filterPanel.getStyleClass().add("ranking-filter-panel");

        MenuButton filterMenu = new MenuButton("Bộ lọc");
        filterMenu.getStyleClass().add("ranking-filter-menu");
        filterMenu.getItems().add(new CustomMenuItem(filterPanel, false));

        Runnable submitFilter = () -> {
            requestRanking.accept(sortRequest(eloEnabled, eloDirection,
                    matchesEnabled, matchesDirection, rateEnabled, rateDirection));
            filterMenu.hide();
        };
        applyFilter.setOnAction(event -> submitFilter.run());
        defaultFilter.setOnAction(event -> {
            eloEnabled.setSelected(true);
            matchesEnabled.setSelected(true);
            rateEnabled.setSelected(true);
            eloDirection.setValue(Direction.DESC);
            matchesDirection.setValue(Direction.DESC);
            rateDirection.setValue(Direction.DESC);
            submitFilter.run();
        });

        TextField search = new TextField();
        search.setPromptText("Tìm tên kỳ thủ...");
        search.getStyleClass().add("lobby-search");
        search.setPrefWidth(210);
        search.textProperty().addListener((observable, oldValue, query) -> {
            String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            filteredRankings.setPredicate(row -> normalized.isEmpty()
                    || row.path("username").asText().toLowerCase(Locale.ROOT).contains(normalized));
        });

        Button backButton = new Button("Quay lại sảnh");
        backButton.getStyleClass().add("btn-secondary");
        backButton.setOnAction(event -> back.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, filterMenu, search, backButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("records-header");

        TableView<JsonNode> table = createTable();
        table.setItems(filteredRankings);

        Label selfRankTitle = new Label("THỨ HẠNG CỦA BẠN");
        selfRankTitle.getStyleClass().add("self-rank-title");
        selfRankValue.getStyleClass().add("self-rank-value");
        Region selfSpacer = new Region();
        HBox.setHgrow(selfSpacer, Priority.ALWAYS);
        HBox selfRankBar = new HBox(12, selfRankTitle, selfSpacer, selfRankValue);
        selfRankBar.setAlignment(Pos.CENTER_LEFT);
        selfRankBar.getStyleClass().add("self-rank-bar");

        VBox card = new VBox(12, header, table, selfRankBar);
        card.getStyleClass().add("lobby-card");
        card.setMaxSize(1200, 720);
        VBox.setVgrow(table, Priority.ALWAYS);

        root = UiSupport.photoRoot("/images/img10.png", card, 0.10, 24);
        root.getStyleClass().addAll("lobby-root", "ranking-root");
        updateRows(rows);
    }

    public Parent root() {
        return root;
    }

    public void updateRows(JsonNode rows) {
        rankings.clear();
        rows.forEach(row -> rankings.add(row.deepCopy()));
        updateSelfRank();
    }

    private TableView<JsonNode> createTable() {
        TableView<JsonNode> table = new TableView<>();
        table.getStyleClass().add("dark-table");

        TableColumn<JsonNode, String> rank = UiSupport.column("Hạng",
                row -> row.path("rank").asText());
        rank.getStyleClass().add("col-center");
        rank.setCellFactory(column -> new TableCell<>() {
            private final Label badge = new Label();
            {
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                int position;
                try {
                    position = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    position = -1;
                }
                badge.setText(position > 0 && position <= 3 ? topRankText(position) : value);
                badge.getStyleClass().setAll("rank-badge");
                if (position == 1)
                    badge.getStyleClass().add("rank-gold");
                else if (position == 2)
                    badge.getStyleClass().add("rank-silver");
                else if (position == 3)
                    badge.getStyleClass().add("rank-bronze");
                setGraphic(badge);
                setText(null);
            }
        });

        TableColumn<JsonNode, String> player = centeredColumn("Kỳ thủ",
                row -> row.path("username").asText());
        TableColumn<JsonNode, String> elo = centeredColumn("Điểm ELO",
                row -> row.path("elo").asText());
        TableColumn<JsonNode, String> wins = centeredColumn("Số trận thắng",
                row -> row.path("wins").asText("0"));
        TableColumn<JsonNode, String> totalMatches = centeredColumn("Tổng trận",
                row -> row.path("totalMatches").asText("0"));
        TableColumn<JsonNode, String> winRate = centeredColumn("Tỷ lệ thắng",
                row -> percent(row.path("winRate").asDouble()));

        for (TableColumn<JsonNode, ?> column : List.of(rank, elo, wins, totalMatches, winRate))
            column.getStyleClass().add("numeric-column");
        for (TableColumn<JsonNode, ?> column : List.of(rank, player, elo, wins, totalMatches, winRate))
            column.setSortable(false);

        table.getColumns().setAll(rank, player, elo, wins, totalMatches, winRate);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("Chưa có dữ liệu kỳ thủ."));
        table.setRowFactory(ignored -> new TableRow<>() {
            @Override
            protected void updateItem(JsonNode item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("current-user-row");
                if (!empty && item != null
                        && username.equals(item.path("username").asText())) {
                    getStyleClass().add("current-user-row");
                }
            }
        });
        return table;
    }

    private void updateSelfRank() {
        JsonNode self = rankings.stream()
                .filter(row -> username.equals(row.path("username").asText()))
                .findFirst().orElse(null);
        if (self == null) {
            selfRankValue.setText("Không tìm thấy dữ liệu kỳ thủ.");
            return;
        }
        selfRankValue.setText("#" + self.path("rank").asInt()
                + "  |  " + username
                + "  |  ELO: " + self.path("elo").asInt()
                + "  |  Thắng: " + self.path("wins").asInt()
                + "/" + self.path("totalMatches").asInt()
                + " (" + percent(self.path("winRate").asDouble()) + ")");
    }

    private static CheckBox criterionCheckBox(String text) {
        CheckBox checkBox = new CheckBox(text);
        checkBox.setSelected(true);
        checkBox.getStyleClass().add("ranking-filter-check");
        return checkBox;
    }

    private static ComboBox<Direction> directionSelector() {
        ComboBox<Direction> selector = new ComboBox<>();
        selector.getItems().setAll(Direction.values());
        selector.setValue(Direction.DESC);
        selector.setPrefWidth(112);
        selector.getStyleClass().add("ranking-filter-direction");
        return selector;
    }

    private static HBox criterionRow(CheckBox enabled, ComboBox<Direction> direction) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, enabled, spacer, direction);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("ranking-filter-row");
        return row;
    }

    private static ObjectNode sortRequest(
            CheckBox eloEnabled, ComboBox<Direction> eloDirection,
            CheckBox matchesEnabled, ComboBox<Direction> matchesDirection,
            CheckBox rateEnabled, ComboBox<Direction> rateDirection) {
        ObjectNode request = JsonProtocol.object();
        ArrayNode sorts = request.putArray("sorts");
        appendSort(sorts, eloEnabled.isSelected(), "ELO", eloDirection.getValue());
        appendSort(sorts, matchesEnabled.isSelected(), "TOTAL_MATCHES", matchesDirection.getValue());
        appendSort(sorts, rateEnabled.isSelected(), "WIN_RATE", rateDirection.getValue());
        return request;
    }

    private static void appendSort(ArrayNode sorts, boolean enabled,
                                   String field, Direction direction) {
        if (enabled)
            sorts.addObject().put("field", field).put("direction", direction.name());
    }

    private static TableColumn<JsonNode, String> centeredColumn(
            String title, java.util.function.Function<JsonNode, String> value) {
        TableColumn<JsonNode, String> column = UiSupport.column(title, value);
        column.getStyleClass().add("col-center");
        return column;
    }

    private static String topRankText(int rank) {
        return switch (rank) {
            case 1 -> "🥇 1";
            case 2 -> "🥈 2";
            case 3 -> "🥉 3";
            default -> String.valueOf(rank);
        };
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value);
    }
}
