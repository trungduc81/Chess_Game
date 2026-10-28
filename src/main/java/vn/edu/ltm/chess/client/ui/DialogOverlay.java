package vn.edu.ltm.chess.client.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import vn.edu.ltm.chess.chess.ChessColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Popup JavaFX nằm trong cửa sổ chính, không tạo Alert hoặc Stage hệ điều hành. */
public final class DialogOverlay {
    public enum Tone {
        DEFAULT("popup-default"),
        WIN("popup-win"),
        LOSE("popup-lose"),
        DRAW("popup-draw"),
        DANGER("popup-danger");

        private final String styleClass;

        Tone(String styleClass) { this.styleClass = styleClass; }
    }

    public record Action(String text, boolean primary, Runnable handler) {
        public Action {
            Objects.requireNonNull(text);
            Objects.requireNonNull(handler);
        }

        public static Action primary(String text, Runnable handler) {
            return new Action(text, true, handler);
        }

        public static Action secondary(String text, Runnable handler) {
            return new Action(text, false, handler);
        }
    }

    private DialogOverlay() { }

    public static void show(Node owner, Tone tone, Node illustration, String title,
                            String message, String badge, String badgeClass,
                            Action... actions) {
        showInternal(owner, tone, illustration, title, message, badge, badgeClass,
                false, actions);
    }

    /** Kết quả chiếu bí làm mờ nền nhẹ để vẫn quan sát được bàn cờ phía sau. */
    public static void showBoardVisible(Node owner, Tone tone, Node illustration, String title,
                                        String message, String badge, String badgeClass,
                                        Action... actions) {
        showInternal(owner, tone, illustration, title, message, badge, badgeClass,
                true, actions);
    }

    private static void showInternal(Node owner, Tone tone, Node illustration, String title,
                                     String message, String badge, String badgeClass,
                                     boolean boardVisible, Action... actions) {
        VBox card = baseCard(tone, title);
        if (illustration != null) card.getChildren().add(0, illustration);

        if (message != null && !message.isBlank()) {
            Label content = new Label(message);
            content.getStyleClass().add("popup-message");
            content.setWrapText(true);
            content.setMaxWidth(350);
            card.getChildren().add(content);
        }
        if (badge != null && !badge.isBlank()) {
            Label eloBadge = new Label(badge);
            eloBadge.getStyleClass().addAll("elo-badge",
                    badgeClass == null || badgeClass.isBlank() ? "elo-neutral" : badgeClass);
            card.getChildren().add(eloBadge);
        }

        StackPane backdrop = backdrop(owner, card);
        if (boardVisible) {
            backdrop.getStyleClass().add("board-visible-overlay");
            card.getStyleClass().add("board-visible-popup-card");
            StackPane.setAlignment(card, Pos.CENTER);
        }
        HBox buttons = actionBar(backdrop, actions);
        if (!buttons.getChildren().isEmpty()) card.getChildren().add(buttons);
        display(owner, backdrop, card);
    }

    public static void information(Node owner, String title, String message) {
        notice(owner, Tone.DEFAULT, "✓", title, message);
    }

    public static void notice(Node owner, Tone tone, String symbol, String title, String message) {
        show(owner, tone, unicodeIcon(symbol), title, message, null, null,
                Action.primary("Đã rõ", () -> { }));
    }

    public static void warning(Node owner, String title, String message) {
        show(owner, Tone.DANGER, unicodeIcon("⚠"), title, message, null, null,
                Action.primary("Đã rõ", () -> { }));
    }

    public static void challenge(Node owner, String challenger, int elo,
                                 Consumer<Boolean> result) {
        Node whiteKnight = pieceIcon("WHITE", "n", 52);
        whiteKnight.setScaleX(-1);
        Node blackKnight = pieceIcon("BLACK", "n", 52);
        HBox illustration = new HBox(4, whiteKnight, blackKnight);
        illustration.setAlignment(Pos.CENTER);
        confirm(owner, Tone.DEFAULT, illustration, "LỜI THÁCH ĐẤU",
                challenger + " (ELO " + elo + ") muốn thách đấu với bạn.",
                "Chấp nhận", "Từ chối", result);
    }

    public static void confirm(Node owner, Tone tone, Node illustration, String title,
                               String message, String primaryText, String secondaryText,
                               Consumer<Boolean> result) {
        show(owner, tone, illustration, title, message, null, null,
                Action.primary(primaryText, () -> result.accept(true)),
                Action.secondary(secondaryText, () -> result.accept(false)));
    }

    public static void disconnected(Node owner, String message, Runnable onClose) {
        show(owner, Tone.DANGER, unicodeIcon("⌁"), "MẤT KẾT NỐI",
                message, null, null,
                Action.primary("Quay lại Đăng nhập", onClose));
    }

    public static void promotion(Node owner, ChessColor color, Consumer<String> selection) {
        VBox choices = new VBox(10);
        Label instruction = new Label("Chọn quân cờ muốn phong cấp");
        instruction.getStyleClass().add("popup-message");
        HBox pieces = new HBox(8);
        pieces.setAlignment(Pos.CENTER);
        for (String piece : List.of("q", "r", "b", "n")) {
            Button button = new Button();
            button.getStyleClass().add("promotion-piece-button");
            button.setGraphic(pieceIcon(color.name(), piece, 48));
            pieces.getChildren().add(button);
            button.setOnAction(event -> {
                close(findBackdrop(button), () -> selection.accept(piece));
            });
        }
        choices.getChildren().addAll(instruction, pieces);
        choices.setAlignment(Pos.CENTER);

        VBox card = baseCard(Tone.DEFAULT, "CHỌN QUÂN PHONG CẤP");
        card.getChildren().add(choices);
        StackPane backdrop = backdrop(owner, card);
        display(owner, backdrop, card);
    }

    public static Node pieceIcon(String color, String piece, double size) {
        String prefix = "BLACK".equalsIgnoreCase(color) ? "b" : "w";
        String path = "/images/" + prefix + piece.toLowerCase() + ".png";
        try (var stream = DialogOverlay.class.getResourceAsStream(path)) {
            if (stream != null) {
                ImageView image = new ImageView(new Image(stream));
                image.setFitWidth(size);
                image.setFitHeight(size);
                image.setPreserveRatio(true);
                image.setSmooth(true);
                return image;
            }
        } catch (Exception ignored) {
            // Ký hiệu chữ giúp popup vẫn hoạt động nếu thiếu tài nguyên ảnh.
        }
        return unicodeIcon(piece.substring(0, 1).toUpperCase());
    }

    public static Label unicodeIcon(String symbol) {
        Label icon = new Label(symbol);
        icon.getStyleClass().add("popup-unicode-icon");
        return icon;
    }

    private static VBox baseCard(Tone tone, String title) {
        Label heading = new Label(title);
        heading.getStyleClass().add("popup-title");
        heading.setWrapText(true);
        heading.setMaxWidth(Double.MAX_VALUE);
        heading.setAlignment(Pos.CENTER);
        heading.setCursor(Cursor.MOVE);
        VBox card = new VBox(12, heading);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().addAll("popup-card", tone.styleClass);
        // Không để StackPane nền kéo giãn thẻ hết chiều cao cửa sổ; chiều cao ôm theo nội dung.
        card.setMaxHeight(Region.USE_PREF_SIZE);
        makeDraggable(card);
        return card;
    }

    private static void makeDraggable(Region card) {
        final double[] dragDelta = new double[2];
        card.setOnMousePressed(event -> {
            if (isInteractive((Node) event.getTarget())) return;
            dragDelta[0] = card.getTranslateX() - event.getSceneX();
            dragDelta[1] = card.getTranslateY() - event.getSceneY();
        });
        card.setOnMouseDragged(event -> {
            if (isInteractive((Node) event.getTarget())) return;
            card.setTranslateX(event.getSceneX() + dragDelta[0]);
            card.setTranslateY(event.getSceneY() + dragDelta[1]);
        });
    }

    private static boolean isInteractive(Node node) {
        while (node != null) {
            if (node instanceof Button || node instanceof ScrollPane || node instanceof TableView) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private static StackPane backdrop(Node owner, VBox card) {
        StackPane backdrop = new StackPane(card);
        backdrop.getStyleClass().add("overlay-backdrop");
        backdrop.setPickOnBounds(true);
        backdrop.setPadding(new Insets(18));
        return backdrop;
    }

    private static HBox actionBar(StackPane backdrop, Action... actions) {
        List<Action> ordered = new ArrayList<>();
        for (Action action : actions) if (action.primary()) ordered.add(action);
        for (Action action : actions) if (!action.primary()) ordered.add(action);

        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER);
        for (Action action : ordered) {
            Button button = new Button(action.text());
            button.getStyleClass().add(action.primary() ? "popup-btn-primary" : "popup-btn-secondary");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setMinWidth(112);
            HBox.setHgrow(button, Priority.ALWAYS);
            button.setOnAction(event -> close(backdrop, action.handler()));
            bar.getChildren().add(button);
        }
        return bar;
    }

    private static void display(Node owner, StackPane backdrop, Region card) {
        StackPane host = overlayHost(owner);
        String stylesheet = Objects.requireNonNull(
                DialogOverlay.class.getResource("/styles/popup.css"),
                "Không tìm thấy popup.css").toExternalForm();
        if (!backdrop.getStylesheets().contains(stylesheet)) backdrop.getStylesheets().add(stylesheet);
        host.getChildren().add(backdrop);

        backdrop.setOpacity(0);
        card.setScaleX(0.85);
        card.setScaleY(0.85);
        FadeTransition fade = new FadeTransition(Duration.millis(160), backdrop);
        fade.setFromValue(0);
        fade.setToValue(1);
        ScaleTransition scale = new ScaleTransition(Duration.millis(160), card);
        scale.setFromX(0.85);
        scale.setFromY(0.85);
        scale.setToX(1);
        scale.setToY(1);
        new ParallelTransition(fade, scale).play();
    }

    private static void close(StackPane backdrop, Runnable afterClose) {
        backdrop.setMouseTransparent(true);
        FadeTransition fade = new FadeTransition(Duration.millis(100), backdrop);
        fade.setToValue(0);
        fade.setOnFinished(event -> {
            if (backdrop.getParent() instanceof StackPane host) host.getChildren().remove(backdrop);
            afterClose.run();
        });
        fade.play();
    }

    private static StackPane findBackdrop(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof StackPane pane
                    && pane.getStyleClass().contains("overlay-backdrop")) return pane;
            current = current.getParent();
        }
        throw new IllegalStateException("Không tìm thấy overlay của popup");
    }

    private static StackPane overlayHost(Node owner) {
        if (owner == null || owner.getScene() == null) {
            throw new IllegalStateException("Không thể mở popup khi giao diện chưa hiển thị");
        }
        if (owner.getScene().getRoot() instanceof StackPane host) return host;
        throw new IllegalStateException("Gốc giao diện phải là StackPane để hiển thị popup");
    }
}
