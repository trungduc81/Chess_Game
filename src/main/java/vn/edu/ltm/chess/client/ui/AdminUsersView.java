package vn.edu.ltm.chess.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Locale;

/** Danh sách và thao tác quản lý tài khoản người chơi. */
public final class AdminUsersView {
    public interface Actions {
        void back();

        void refresh();

        void lock(String username, String reason);

        void unlock(String username);

        void kick(String username);
    }

    private final String adminUsername;
    private final Actions actions;
    private final ObservableList<JsonNode> users = FXCollections.observableArrayList();
    private final FilteredList<JsonNode> filteredUsers = new FilteredList<>(users);
    private final Label count = new Label();
    private final StackPane root;

    public AdminUsersView(String adminUsername, JsonNode rows, Actions actions) {
        this.adminUsername = adminUsername;
        this.actions = actions;

        Label title = new Label("QUẢN LÝ NGƯỜI CHƠI");
        title.getStyleClass().add("admin-title");
        count.getStyleClass().add("admin-count-badge");
        TextField search = new TextField();
        search.setPromptText("Tìm theo tên tài khoản...");
        search.getStyleClass().add("admin-search");
        search.setPrefWidth(250);
        search.textProperty().addListener((observable, oldValue, newValue) -> {
            String query = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            filteredUsers.setPredicate(user -> query.isEmpty()
                    || user.path("username").asText().toLowerCase(Locale.ROOT).contains(query));
            updateCount();
        });
        Button refresh = button("Làm mới", "btn-secondary", actions::refresh);
        Button back = button("Quay lại Dashboard", "btn-secondary", actions::back);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, count, spacer, search, refresh, back);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("admin-header");

        TableView<JsonNode> table = createTable();
        table.setItems(filteredUsers);
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

    public void updateRows(JsonNode rows) {
        users.clear();
        JsonNode source = rows != null && rows.has("users") ? rows.path("users") : rows;
        if (source != null && source.isArray()) {
            source.forEach(row -> users.add(row.deepCopy()));
        }
        updateCount();
    }

    private TableView<JsonNode> createTable() {
        TableView<JsonNode> table = new TableView<>();
        table.getStyleClass().addAll("dark-table", "admin-table");

        TableColumn<JsonNode, String> username = centered("Tài khoản",
                row -> row.path("username").asText());
        TableColumn<JsonNode, String> role = centered("Vai trò",
                row -> row.path("role").asText("PLAYER"));
        TableColumn<JsonNode, String> elo = centered("ELO",
                row -> row.path("elo").asText("1200"));
        TableColumn<JsonNode, String> matches = centered("Tổng trận",
                row -> row.path("totalMatches").asText("0"));
        TableColumn<JsonNode, String> online = centered("Kết nối",
                this::onlineStatus);
        online.setCellFactory(column -> connectionBadgeCell());
        TableColumn<JsonNode, String> account = centered("Trạng thái TK",
                row -> row.path("accountStatus").asText("ACTIVE"));
        account.setCellFactory(column -> badgeCell("ACTIVE", "admin-status-active",
                "LOCKED", "admin-status-locked"));

        TableColumn<JsonNode, JsonNode> action = new TableColumn<>("Hành động");
        action.getStyleClass().add("col-center");
        action.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyObjectWrapper<>(cell.getValue()));
        action.setCellFactory(column -> new TableCell<>() {
            private final Button accountAction = new Button();
            private final Button kick = new Button("Ngắt kết nối");
            private final HBox box = new HBox(6, accountAction, kick);
            {
                setAlignment(Pos.CENTER);
                box.setAlignment(Pos.CENTER);
                accountAction.getStyleClass().addAll("small-button", "btn-danger");
                kick.getStyleClass().addAll("small-button", "btn-secondary");
            }

            @Override
            protected void updateItem(JsonNode user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setGraphic(null);
                    return;
                }
                String name = user.path("username").asText();
                boolean locked = "LOCKED".equalsIgnoreCase(user.path("accountStatus").asText());
                boolean protectedAccount = adminUsername.equals(name)
                        || "ADMIN".equalsIgnoreCase(user.path("role").asText());
                accountAction.setText(locked ? "Mở khóa" : "Khóa");
                accountAction.getStyleClass().setAll("small-button",
                        locked ? "btn-primary" : "btn-danger");
                accountAction.setDisable(protectedAccount);
                accountAction.setOnAction(event -> {
                    if (locked) {
                        DialogOverlay.confirm(root, DialogOverlay.Tone.DEFAULT, null,
                                "MỞ KHÓA TÀI KHOẢN",
                                "Cho phép " + name + " đăng nhập lại vào hệ thống?",
                                "Mở khóa", "Hủy", accepted -> {
                                    if (accepted) actions.unlock(name);
                                });
                    } else {
                        showLockDialog(name);
                    }
                });
                kick.setDisable(protectedAccount || !isOnline(user));
                kick.setOnAction(event -> DialogOverlay.confirm(root, DialogOverlay.Tone.DANGER,
                        null, "NGẮT KẾT NỐI",
                        "Ngắt kết nối hiện tại của " + name + "?",
                        "Ngắt kết nối", "Hủy", accepted -> {
                            if (accepted) actions.kick(name);
                        }));
                setGraphic(box);
            }
        });

        table.getColumns().setAll(username, role, elo, matches, online, account, action);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("Chưa có tài khoản nào trong hệ thống."));
        table.getColumns().forEach(column -> column.setSortable(false));
        return table;
    }

    private void showLockDialog(String username) {
        Label title = new Label("KHÓA TÀI KHOẢN");
        title.getStyleClass().add("admin-dialog-title");
        Label message = new Label("Nhập lý do khóa tài khoản " + username + ".");
        message.getStyleClass().add("admin-dialog-message");
        TextField reason = new TextField();
        reason.setPromptText("Lý do khóa...");
        reason.getStyleClass().add("admin-dialog-input");
        reason.setPrefWidth(360);
        Label validation = new Label();
        validation.getStyleClass().add("admin-dialog-error");
        Button confirm = new Button("Khóa tài khoản");
        confirm.getStyleClass().add("btn-danger");
        Button cancel = new Button("Hủy");
        cancel.getStyleClass().add("btn-secondary");
        HBox actionsBar = new HBox(8, confirm, cancel);
        actionsBar.setAlignment(Pos.CENTER);
        VBox dialog = new VBox(12, title, message, reason, validation, actionsBar);
        dialog.setAlignment(Pos.CENTER);
        dialog.getStyleClass().add("admin-dialog-card");
        dialog.setMaxSize(440, Region.USE_PREF_SIZE);
        StackPane backdrop = new StackPane(dialog);
        backdrop.getStyleClass().add("admin-dialog-backdrop");
        backdrop.setPadding(new Insets(18));
        root.getChildren().add(backdrop);
        cancel.setOnAction(event -> root.getChildren().remove(backdrop));
        confirm.setOnAction(event -> {
            String value = reason.getText() == null ? "" : reason.getText().trim();
            if (value.isEmpty()) {
                validation.setText("Vui lòng nhập lý do khóa tài khoản.");
                return;
            }
            root.getChildren().remove(backdrop);
            actions.lock(username, value);
        });
        reason.setOnAction(event -> confirm.fire());
        reason.requestFocus();
    }

    private void updateCount() {
        int shown = filteredUsers.size();
        int total = users.size();
        count.setText(shown == total ? String.valueOf(total) : shown + "/" + total);
    }

    private String onlineStatus(JsonNode row) {
        if (!row.path("online").asBoolean(false)) return "OFFLINE";
        return row.path("playerStatus").asText(
                row.path("onlineStatus").asText(row.path("status").asText("ONLINE")))
                .toUpperCase(Locale.ROOT);
    }

    private boolean isOnline(JsonNode user) {
        return !"OFFLINE".equals(onlineStatus(user));
    }

    private static TableCell<JsonNode, String> connectionBadgeCell() {
        return new TableCell<>() {
            private final Label badge = new Label();
            {
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    return;
                }
                String normalized = value.toUpperCase(Locale.ROOT);
                String text;
                String style;
                if ("BUSY".equals(normalized) || "IN_GAME".equals(normalized)) {
                    text = "ĐANG BẬN";
                    style = "admin-status-busy";
                } else if ("FREE".equals(normalized) || "IDLE".equals(normalized)) {
                    text = "ĐANG RẢNH";
                    style = "admin-status-online";
                } else if ("ONLINE".equals(normalized)) {
                    text = "ONLINE";
                    style = "admin-status-online";
                } else {
                    text = "OFFLINE";
                    style = "admin-status-offline";
                }
                badge.setText(text);
                badge.getStyleClass().setAll("admin-status-badge", style);
                setGraphic(badge);
            }
        };
    }

    private static TableCell<JsonNode, String> badgeCell(String positive, String positiveClass,
                                                          String negative, String negativeClass) {
        return new TableCell<>() {
            private final Label badge = new Label();
            {
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    return;
                }
                boolean isPositive = positive.equalsIgnoreCase(value);
                badge.setText(isPositive ? positive : negative);
                badge.getStyleClass().setAll("admin-status-badge",
                        isPositive ? positiveClass : negativeClass);
                setGraphic(badge);
            }
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
