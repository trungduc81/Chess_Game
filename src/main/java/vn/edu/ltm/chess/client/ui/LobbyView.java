package vn.edu.ltm.chess.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class LobbyView {
    public interface Actions {
        void challenge(String target);

        void showProfile();

        void showRanking();

        void logout();
    }

    private final String username;
    private final Actions actions;
    private final ObservableList<JsonNode> players = FXCollections.observableArrayList();
    private final FilteredList<JsonNode> filteredPlayers = new FilteredList<>(players);
    private final TableView<JsonNode> onlineTable = new TableView<>(filteredPlayers);
    private final Label onlineTitle = new Label();
    private final Label eloBadge = new Label();
    private final Label emptyMessage = new Label();
    private final ToggleButton readyOnlyFilter = new ToggleButton("● Chỉ hiện người rảnh");
    private final TextField searchField = new TextField();
    private final StackPane root;

    public LobbyView(String username, int currentElo, Actions actions) {
        this.username = username;
        this.actions = actions;
        eloBadge.setText("ELO: " + currentElo);
        eloBadge.getStyleClass().add("current-elo-badge");

        Label greeting = new Label("Xin chào, " + username);
        greeting.getStyleClass().add("lobby-greeting");
        HBox identity = new HBox(10, greeting, eloBadge);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.getStyleClass().add("profile-trigger");
        identity.setFocusTraversable(true);
        identity.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY)
                actions.showProfile();
        });
        identity.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                actions.showProfile();
            }
        });

        Button history = navigationButton("Hồ sơ", "btn-secondary");
        Button ranking = navigationButton("Bảng xếp hạng", "btn-secondary");
        Button sound = navigationButton(soundButtonText(), "btn-secondary");
        Button logout = navigationButton("Đăng xuất", "btn-danger");
        history.setOnAction(event -> actions.showProfile());
        ranking.setOnAction(event -> actions.showRanking());
        sound.setOnAction(event -> {
            SoundManager.toggleMuted();
            sound.setText(soundButtonText());
        });
        logout.setOnAction(event -> actions.logout());
        HBox navigation = new HBox(9, history, ranking, sound, logout);
        navigation.setAlignment(Pos.CENTER_RIGHT);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(16, identity, headerSpacer, navigation);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("lobby-header");

        onlineTitle.getStyleClass().add("section-title");
        updateOnlineTitle();
        readyOnlyFilter.getStyleClass().add("filter-toggle");
        readyOnlyFilter.setOnAction(event -> applyFilters());
        searchField.setPromptText("Tìm tên hoặc ELO...");
        searchField.getStyleClass().add("lobby-search");
        searchField.setPrefWidth(230);
        searchField.textProperty().addListener((observable, oldValue, query) -> applyFilters());
        HBox filters = new HBox(8, readyOnlyFilter, searchField);
        filters.setAlignment(Pos.CENTER_RIGHT);

        Region listSpacer = new Region();
        HBox.setHgrow(listSpacer, Priority.ALWAYS);
        HBox listHeader = new HBox(12, onlineTitle, listSpacer, filters);
        listHeader.setAlignment(Pos.CENTER_LEFT);

        configureTable();
        VBox panel = new VBox(18, header, listHeader, onlineTable);
        panel.getStyleClass().add("lobby-card");
        panel.setMaxSize(1180, 760);
        VBox.setVgrow(onlineTable, Priority.ALWAYS);

        root = UiSupport.photoRoot("/images/img9_fixed.png", panel, 0.10, 24);
        root.getStyleClass().add("lobby-root");
    }

    public Parent root() {
        return root;
    }

    public void updateOnline(JsonNode data) {
        List<JsonNode> opponents = new ArrayList<>();
        data.forEach(node -> {
            if (username.equals(node.path("username").asText())) {
                eloBadge.setText("ELO: " + node.path("elo").asInt(1200));
            } else {
                opponents.add(node);
            }
        });
        opponents.sort(Comparator
                .comparing((JsonNode player) -> !isReady(player))
                .thenComparing(player -> player.path("username").asText(),
                        String.CASE_INSENSITIVE_ORDER));
        players.setAll(opponents);
        applyFilters();
    }

    private void configureTable() {
        TableColumn<JsonNode, String> name = UiSupport.column("Kỳ thủ",
                node -> node.path("username").asText());
        name.getStyleClass().add("col-center");
        name.setCellFactory(column -> new TableCell<>() {
            private final javafx.scene.Node knight = DialogOverlay.pieceIcon("WHITE", "n", 24);
            {
                getStyleClass().add("player-name-cell");
                setAlignment(Pos.CENTER);
                setContentDisplay(ContentDisplay.LEFT);
                setGraphicTextGap(8);
                setTextOverrun(OverrunStyle.ELLIPSIS);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setGraphic(empty ? null : knight);
                setText(empty ? null : value);
            }
        });

        TableColumn<JsonNode, String> score = UiSupport.column("Điểm ELO",
                node -> node.path("elo").asText("1200"));
        score.getStyleClass().add("col-center");
        score.setCellFactory(column -> new TableCell<>() {
            {
                getStyleClass().add("elo-cell");
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
            }
        });

        TableColumn<JsonNode, String> status = UiSupport.column("Trạng thái",
                node -> node.path("status").asText("FREE"));
        status.getStyleClass().add("col-center");
        status.setCellFactory(column -> new TableCell<>() {
            private final Label badge = new Label();
            {
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                boolean ready = isReadyStatus(value);
                badge.setText(ready ? "● Đang rảnh" : "● Đang bận");
                badge.getStyleClass().setAll("status-badge", ready ? "status-ready" : "status-busy");
                setGraphic(badge);
                setText(null);
            }
        });

        TableColumn<JsonNode, JsonNode> action = new TableColumn<>("Hành động");
        action.getStyleClass().add("col-center");
        action.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        action.setCellFactory(column -> new TableCell<>() {
            private final Button challenge = new Button();
            {
                setAlignment(Pos.CENTER);
                challenge.getStyleClass().addAll("btn-primary", "small-button");
                challenge.setOnAction(event -> {
                    JsonNode player = getItem();
                    if (player != null)
                        actions.challenge(player.path("username").asText());
                });
            }

            @Override
            protected void updateItem(JsonNode player, boolean empty) {
                super.updateItem(player, empty);
                if (empty || player == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                boolean ready = isReady(player);
                challenge.setText(ready ? "⚔ Thách đấu" : "Đang bận");
                challenge.setDisable(!ready);
                setGraphic(challenge);
                setText(null);
            }
        });

        name.setSortable(false);
        score.setSortable(false);
        status.setSortable(false);
        action.setSortable(false);
        onlineTable.getColumns().setAll(name, score, status, action);
        onlineTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        onlineTable.getStyleClass().add("dark-table");
        onlineTable.setPlaceholder(emptyPlaceholder());
        onlineTable.setRowFactory(table -> {
            TableRow<JsonNode> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && isReady(row.getItem())) {
                    actions.challenge(row.getItem().path("username").asText());
                }
            });
            return row;
        });
        onlineTable.setOnKeyPressed(event -> {
            JsonNode selected = onlineTable.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER && selected != null && isReady(selected)) {
                actions.challenge(selected.path("username").asText());
            }
        });
    }

    private VBox emptyPlaceholder() {
        javafx.scene.Node icon = DialogOverlay.pieceIcon("BLACK", "p", 36);
        icon.setOpacity(0.45);
        emptyMessage.setText("Hiện chưa có kỳ thủ nào khác đang online...");
        emptyMessage.getStyleClass().add("empty-player-text");
        VBox placeholder = new VBox(8, icon, emptyMessage);
        placeholder.setAlignment(Pos.CENTER);
        return placeholder;
    }

    private void applyFilters() {
        String query = searchField.getText() == null
                ? ""
                : searchField.getText().trim().toLowerCase(Locale.ROOT);
        boolean readyOnly = readyOnlyFilter.isSelected();
        filteredPlayers.setPredicate(player -> {
            if (readyOnly && !isReady(player))
                return false;
            if (query.isEmpty())
                return true;
            String playerName = player.path("username").asText().toLowerCase(Locale.ROOT);
            String elo = player.path("elo").asText("");
            return playerName.contains(query) || elo.contains(query);
        });
        updateOnlineTitle();
        updatePlaceholder();
    }

    private void updateOnlineTitle() {
        int total = players.size();
        boolean filtering = readyOnlyFilter.isSelected()
                || (searchField.getText() != null && !searchField.getText().isBlank());
        String count = filtering ? filteredPlayers.size() + "/" + total : String.valueOf(total);
        onlineTitle.setText("DANH SÁCH KỲ THỦ ĐANG ONLINE (" + count + ")");
    }

    private void updatePlaceholder() {
        emptyMessage.setText(players.isEmpty()
                ? "Hiện chưa có kỳ thủ nào khác đang online..."
                : "Không tìm thấy kỳ thủ nào khớp với bộ lọc.");
    }

    private static boolean isReady(JsonNode player) {
        return isReadyStatus(player.path("status").asText());
    }

    private static boolean isReadyStatus(String status) {
        String normalized = status == null ? "" : status.toUpperCase(Locale.ROOT);
        return "FREE".equals(normalized) || "IDLE".equals(normalized);
    }

    private static Button navigationButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        return button;
    }

    private static String soundButtonText() {
        return SoundManager.isMuted() ? "Âm thanh: Tắt" : "Âm thanh: Bật";
    }
}
