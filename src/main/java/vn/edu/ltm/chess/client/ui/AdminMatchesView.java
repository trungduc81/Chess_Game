package vn.edu.ltm.chess.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.beans.property.ReadOnlyObjectWrapper;
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
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import vn.edu.ltm.chess.protocol.JsonProtocol;

/** Danh sách toàn bộ trận đấu dành cho quản trị viên. */
public final class AdminMatchesView {
    public interface Actions {
        void back();

        void refresh();

        void filter(JsonNode request);

        void replay(JsonNode match);
    }

    private static final DateTimeFormatter INPUT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ObservableList<JsonNode> matches = FXCollections.observableArrayList();
    private final FilteredList<JsonNode> filteredMatches = new FilteredList<>(matches);
    private final Label count = new Label();
    private final StackPane root;

    public AdminMatchesView(JsonNode rows, Actions actions, JsonNode currentFilter) {
        Label title = new Label("QUẢN LÝ TRẬN ĐẤU");
        title.getStyleClass().add("admin-title");
        count.getStyleClass().add("admin-count-badge");
        TextField search = new TextField();
        search.setPromptText("Tìm người chơi hoặc mã trận...");
        search.getStyleClass().add("admin-search");
        search.setPrefWidth(270);
        search.textProperty().addListener((observable, oldValue, value) -> {
            String query = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            filteredMatches.setPredicate(match -> query.isEmpty()
                    || value(match, "gameUid", "gameId", "id").toLowerCase(Locale.ROOT).contains(query)
                    || value(match, "white", "whitePlayer", "whiteUsername").toLowerCase(Locale.ROOT).contains(query)
                    || value(match, "black", "blackPlayer", "blackUsername").toLowerCase(Locale.ROOT).contains(query));
            updateCount();
        });
        Button refresh = button("Làm mới", "btn-secondary", actions::refresh);
        Button back = button("Quay lại Dashboard", "btn-secondary", actions::back);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        MenuButton filter = createFilter(actions, currentFilter);
        HBox header = new HBox(10, title, count, spacer, filter, search, refresh, back);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("admin-header");

        TableView<JsonNode> table = createTable(actions);
        table.setItems(filteredMatches);
        VBox card = new VBox(16, header, table);
        card.getStyleClass().add("admin-card");
        card.setMaxSize(1340, 780);
        VBox.setVgrow(table, Priority.ALWAYS);

        root = UiSupport.photoRoot("/images/img10.png", card, 0.13, 24);
        root.getStyleClass().add("admin-root");
        updateRows(rows);
    }

    public Parent root() {
        return root;
    }

    private static MenuButton createFilter(Actions actions, JsonNode currentFilter) {
        MenuButton menu = new MenuButton("Bộ lọc");
        menu.getStyleClass().add("ranking-filter-menu");
        Label hint = new Label("Ưu tiên từ trên xuống");
        hint.getStyleClass().add("ranking-filter-hint");
        List<String> fields = List.of("TIME", "MOVE_COUNT", "ID");
        List<String> labels = List.of("Thời gian", "Số nước", "Mã trận");
        List<CheckBox> enabled = new java.util.ArrayList<>();
        List<ComboBox<String>> directions = new java.util.ArrayList<>();
        VBox panel = new VBox(9, hint);
        panel.getStyleClass().add("ranking-filter-panel");
        boolean defaultFilter = !currentFilter.path("sorts").isArray()
                || currentFilter.path("sorts").isEmpty();
        for (int index = 0; index < fields.size(); index++) {
            CheckBox check = new CheckBox(labels.get(index));
            check.getStyleClass().add("ranking-filter-check");
            check.setSelected(defaultFilter);
            ComboBox<String> direction = new ComboBox<>();
            direction.getItems().setAll("Tăng dần", "Giảm dần");
            direction.setValue("Giảm dần");
            direction.setPrefWidth(112);
            direction.getStyleClass().add("ranking-filter-direction");
            for (JsonNode sort : currentFilter.path("sorts")) {
                if (fields.get(index).equals(sort.path("field").asText())) {
                    check.setSelected(true);
                    direction.setValue("ASC".equals(sort.path("direction").asText())
                            ? "Tăng dần" : "Giảm dần");
                }
            }
            direction.disableProperty().bind(check.selectedProperty().not());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(10, check, spacer, direction);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("ranking-filter-row");
            panel.getChildren().add(row);
            enabled.add(check);
            directions.add(direction);
        }
        Button apply = new Button("Lọc");
        apply.getStyleClass().add("ranking-filter-apply");
        apply.disableProperty().bind(enabled.get(0).selectedProperty()
                .or(enabled.get(1).selectedProperty()).or(enabled.get(2).selectedProperty()).not());
        Runnable submit = () -> {
            var request = JsonProtocol.object();
            var sorts = request.putArray("sorts");
            for (int index = 0; index < fields.size(); index++) {
                if (enabled.get(index).isSelected()) {
                    sorts.addObject().put("field", fields.get(index)).put("direction",
                            "Tăng dần".equals(directions.get(index).getValue()) ? "ASC" : "DESC");
                }
            }
            actions.filter(request);
            menu.hide();
        };
        apply.setOnAction(event -> submit.run());
        Button defaults = new Button("Mặc định");
        defaults.getStyleClass().add("ranking-filter-default");
        defaults.setOnAction(event -> {
            enabled.forEach(check -> check.setSelected(true));
            directions.forEach(direction -> direction.setValue("Giảm dần"));
            submit.run();
        });
        HBox buttons = new HBox(8, apply, defaults);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        panel.getChildren().add(buttons);
        menu.getItems().add(new CustomMenuItem(panel, false));
        return menu;
    }

    public void updateRows(JsonNode rows) {
        matches.clear();
        JsonNode source = rows != null && rows.has("matches") ? rows.path("matches") : rows;
        if (source != null && source.isArray()) {
            source.forEach(row -> matches.add(row.deepCopy()));
        }
        updateCount();
    }

    private TableView<JsonNode> createTable(Actions actions) {
        TableView<JsonNode> table = new TableView<>();
        table.getStyleClass().addAll("dark-table", "admin-table");
        TableColumn<JsonNode, String> id = centered("Mã trận",
                row -> value(row, "gameUid", "gameId", "id"));
        TableColumn<JsonNode, String> time = centered("Thời gian",
                row -> displayTime(value(row, "endedAt", "startedAt", "createdAt")));
        TableColumn<JsonNode, String> white = centered("Trắng",
                row -> value(row, "white", "whitePlayer", "whiteUsername"));
        TableColumn<JsonNode, String> black = centered("Đen",
                row -> value(row, "black", "blackPlayer", "blackUsername"));
        TableColumn<JsonNode, String> result = centered("Kết quả",
                row -> resultText(row.path("result").asText()));
        TableColumn<JsonNode, String> reason = centered("Lý do",
                row -> row.path("reason").asText());
        TableColumn<JsonNode, String> moves = centered("Số nước",
                row -> value(row, "moveCount", "totalPly", "plies"));
        TableColumn<JsonNode, JsonNode> action = new TableColumn<>("Hành động");
        action.getStyleClass().add("col-center");
        action.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        action.setCellFactory(column -> new TableCell<>() {
            private final Button replay = new Button("Xem lại");
            {
                setAlignment(Pos.CENTER);
                replay.getStyleClass().addAll("btn-primary", "small-button");
            }

            @Override
            protected void updateItem(JsonNode match, boolean empty) {
                super.updateItem(match, empty);
                if (empty || match == null) {
                    setGraphic(null);
                    return;
                }
                replay.setOnAction(event -> actions.replay(match));
                setGraphic(replay);
            }
        });
        table.getColumns().setAll(id, time, white, black, result, reason, moves, action);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("Chưa có trận đấu nào được lưu."));
        table.getColumns().forEach(column -> column.setSortable(false));
        return table;
    }

    private void updateCount() {
        int shown = filteredMatches.size();
        int total = matches.size();
        count.setText(shown == total ? String.valueOf(total) : shown + "/" + total);
    }

    private static String value(JsonNode row, String... fields) {
        for (String field : fields) {
            JsonNode value = row.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private static String displayTime(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            if (raw.chars().allMatch(Character::isDigit)) {
                return Instant.ofEpochMilli(Long.parseLong(raw))
                        .atZone(ZoneId.systemDefault()).format(DISPLAY_TIME);
            }
            return LocalDateTime.parse(raw.replace('T', ' '), INPUT_TIME).format(DISPLAY_TIME);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String resultText(String result) {
        return switch (result) {
            case "WHITE_WIN" -> "Trắng thắng";
            case "BLACK_WIN" -> "Đen thắng";
            case "DRAW" -> "Hòa";
            default -> result;
        };
    }

    private static TableColumn<JsonNode, String> centered(
            String title, java.util.function.Function<JsonNode, String> value) {
        TableColumn<JsonNode, String> column = UiSupport.column(title, value);
        column.getStyleClass().add("col-center");
        return column;
    }

    private static Button button(String text, String styleClass, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        button.setOnAction(event -> action.run());
        return button;
    }
}
