package vn.edu.ltm.chess.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Màn hình tổng quan dành cho quản trị viên. */
public final class AdminDashboardView {
    public interface Actions {
        void showUsers();

        void showMatches();

        void refresh();

        void logout();
    }

    private final Label totalUsersValue = statValue();
    private final Label onlineUsersValue = statValue();
    private final Label activeGamesValue = statValue();
    private final Label totalMatchesValue = statValue();
    private final StackPane root;

    public AdminDashboardView(String adminUsername, JsonNode statistics, Actions actions) {
        Label title = new Label("BẢNG ĐIỀU KHIỂN QUẢN TRỊ");
        title.getStyleClass().add("admin-title");
        Label identity = new Label("Quản trị viên: " + adminUsername);
        identity.getStyleClass().add("admin-identity");

        Button refresh = button("Làm mới", "btn-secondary", actions::refresh);
        Button logout = button("Đăng xuất", "btn-danger", actions::logout);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, spacer, identity, refresh, logout);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("admin-header");

        GridPane statisticsGrid = new GridPane();
        statisticsGrid.setHgap(14);
        statisticsGrid.setVgap(14);
        statisticsGrid.add(statCard("TỔNG NGƯỜI CHƠI", totalUsersValue, "Tài khoản kỳ thủ đã đăng ký"), 0, 0);
        statisticsGrid.add(statCard("ĐANG ONLINE", onlineUsersValue, "Người chơi đang kết nối"), 1, 0);
        statisticsGrid.add(statCard("VÁN ĐANG DIỄN RA", activeGamesValue, "Phiên đấu trực tiếp"), 2, 0);
        statisticsGrid.add(statCard("TỔNG VÁN ĐẤU", totalMatchesValue, "Ván đã được lưu"), 3, 0);
        for (int index = 0; index < 4; index++) {
            javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
            column.setPercentWidth(25);
            column.setHgrow(Priority.ALWAYS);
            column.setFillWidth(true);
            statisticsGrid.getColumnConstraints().add(column);
        }

        Button users = navigationCard("QUẢN LÝ NGƯỜI CHƠI",
                "Tra cứu tài khoản, khóa hoặc mở khóa và quản lý kết nối.", actions::showUsers);
        Button matches = navigationCard("QUẢN LÝ TRẬN ĐẤU",
                "Tra cứu kết quả, thông tin chi tiết và xem lại toàn bộ ván đấu.", actions::showMatches);
        GridPane navigation = new GridPane();
        navigation.setHgap(16);
        javafx.scene.layout.ColumnConstraints left = new javafx.scene.layout.ColumnConstraints();
        left.setPercentWidth(50);
        left.setHgrow(Priority.ALWAYS);
        javafx.scene.layout.ColumnConstraints right = new javafx.scene.layout.ColumnConstraints();
        right.setPercentWidth(50);
        right.setHgrow(Priority.ALWAYS);
        navigation.getColumnConstraints().addAll(left, right);
        navigation.add(users, 0, 0);
        navigation.add(matches, 1, 0);

        Label sectionTitle = new Label("CHỨC NĂNG QUẢN TRỊ");
        sectionTitle.getStyleClass().add("admin-section-title");
        VBox body = new VBox(20, header, statisticsGrid, sectionTitle, navigation);
        body.getStyleClass().add("admin-card");
        body.setMaxSize(1280, 720);

        root = UiSupport.photoRoot("/images/img10.png", body, 0.13, 24);
        root.getStyleClass().add("admin-root");
        updateStatistics(statistics);
    }

    public Parent root() {
        return root;
    }

    public void updateStatistics(JsonNode statistics) {
        JsonNode data = statistics != null && statistics.has("statistics")
                ? statistics.path("statistics") : statistics;
        totalUsersValue.setText(number(data, "totalUsers"));
        onlineUsersValue.setText(number(data, "onlineUsers"));
        activeGamesValue.setText(number(data, "activeGames"));
        totalMatchesValue.setText(number(data, "totalMatches"));
    }

    private static VBox statCard(String titleText, Label value, String descriptionText) {
        Label title = new Label(titleText);
        title.getStyleClass().add("admin-stat-title");
        Label description = new Label(descriptionText);
        description.getStyleClass().add("admin-stat-description");
        VBox card = new VBox(10, title, value, description);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("admin-stat-card");
        card.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private static Label statValue() {
        Label value = new Label("0");
        value.getStyleClass().add("admin-stat-value");
        return value;
    }

    private static Button navigationCard(String title, String description, Runnable action) {
        Label heading = new Label(title);
        heading.getStyleClass().add("admin-nav-title");
        Label content = new Label(description);
        content.setWrapText(true);
        content.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        content.getStyleClass().add("admin-nav-description");
        VBox graphic = new VBox(8, heading, content);
        graphic.setAlignment(Pos.CENTER);
        Button button = new Button();
        button.setGraphic(graphic);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMaxHeight(Double.MAX_VALUE);
        button.getStyleClass().add("admin-nav-card");
        button.setOnAction(event -> action.run());
        GridPane.setHgrow(button, Priority.ALWAYS);
        return button;
    }

    private static Button button(String text, String styleClass, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static String number(JsonNode data, String field) {
        return data == null ? "0" : String.valueOf(data.path(field).asLong(0));
    }
}
