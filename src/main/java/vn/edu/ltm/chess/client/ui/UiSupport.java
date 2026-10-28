package vn.edu.ltm.chess.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.control.TableColumn;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;

import java.util.Objects;
import java.util.function.Function;

public final class UiSupport {
    private UiSupport() { }

    public static StackPane photoRoot(String imagePath, Region content, double dimOpacity, double margin) {
        return photoRoot(imagePath, content, dimOpacity, margin, BackgroundPosition.CENTER);
    }

    public static StackPane photoRoot(String imagePath, Region content, double dimOpacity, double margin,
                                      double verticalPositionPercent) {
        BackgroundPosition position = new BackgroundPosition(
                Side.LEFT, 50, true,
                Side.TOP, verticalPositionPercent, true);
        return photoRoot(imagePath, content, dimOpacity, margin, position);
    }

    private static StackPane photoRoot(String imagePath, Region content, double dimOpacity, double margin,
                                       BackgroundPosition position) {
        Image image = new Image(Objects.requireNonNull(
                UiSupport.class.getResourceAsStream(imagePath), "Không tìm thấy ảnh " + imagePath));
        BackgroundSize size = new BackgroundSize(100, 100, true, true, false, true);
        BackgroundImage backgroundImage = new BackgroundImage(image,
                BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                position, size);

        Region dimmer = new Region();
        dimmer.setMouseTransparent(true);
        dimmer.setBackground(new Background(new BackgroundFill(
                Color.rgb(0, 0, 0, dimOpacity), CornerRadii.EMPTY, Insets.EMPTY)));

        StackPane root = new StackPane(dimmer, content);
        root.setBackground(new Background(backgroundImage));
        StackPane.setMargin(content, new Insets(margin));
        return root;
    }

    public static TableColumn<JsonNode, String> column(
            String title, Function<JsonNode, String> value) {
        TableColumn<JsonNode, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

}
