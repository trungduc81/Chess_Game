package vn.edu.ltm.chess.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class LoginView {
    @FunctionalInterface
    public interface AuthHandler {
        void authenticate(boolean register, String username, String password);
    }

    private final TextField usernameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final PasswordField confirmPasswordField = new PasswordField();
    private final VBox confirmPasswordBox = new VBox(6);
    private final Button primaryButton = new Button();
    private final Button modeButton = new Button();
    private final Label modePrompt = new Label();
    private final Label status = new Label();
    private final Parent root;
    private boolean isRegisterMode = false;
    private boolean submitting = false;

    public LoginView(AuthHandler authHandler) {
        usernameField.setPromptText("Nhập tên tài khoản...");
        passwordField.setPromptText("Nhập mật khẩu...");
        confirmPasswordField.setPromptText("Nhập lại mật khẩu...");
        usernameField.getStyleClass().add("modern-input");
        passwordField.getStyleClass().add("modern-input");
        confirmPasswordField.getStyleClass().add("modern-input");
        usernameField.setMaxWidth(Double.MAX_VALUE);
        passwordField.setMaxWidth(Double.MAX_VALUE);
        confirmPasswordField.setMaxWidth(Double.MAX_VALUE);

        Label userLabel = new Label("Tên đăng nhập");
        userLabel.getStyleClass().add("field-label");
        VBox userBox = new VBox(6, userLabel, usernameField);
        userBox.setAlignment(Pos.CENTER_LEFT);

        Label passwordLabel = new Label("Mật khẩu");
        passwordLabel.getStyleClass().add("field-label");
        VBox passwordBox = new VBox(6, passwordLabel, passwordField);
        passwordBox.setAlignment(Pos.CENTER_LEFT);

        Label confirmPasswordLabel = new Label("Xác nhận mật khẩu");
        confirmPasswordLabel.getStyleClass().add("field-label");
        confirmPasswordBox.getChildren().addAll(confirmPasswordLabel, confirmPasswordField);
        confirmPasswordBox.setAlignment(Pos.CENTER_LEFT);

        primaryButton.getStyleClass().add("btn-primary");
        modeButton.getStyleClass().add("btn-link");
        primaryButton.setMaxWidth(Double.MAX_VALUE);

        StackPane logo = new StackPane(DialogOverlay.pieceIcon("WHITE", "r", 30));
        logo.getStyleClass().add("brand-logo");
        Label brandTitle = new Label("CHESS MASTER");
        brandTitle.getStyleClass().add("brand-title");
        Region brandUnderline = new Region();
        brandUnderline.getStyleClass().add("brand-underline");
        Label brandSubtitle = new Label("NỀN TẢNG CỜ VUA TRỰC TUYẾN");
        brandSubtitle.getStyleClass().add("brand-subtitle");
        VBox branding = new VBox(9, logo, brandTitle, brandUnderline, brandSubtitle);
        branding.getStyleClass().add("login-branding");
        branding.setAlignment(Pos.CENTER);

        Region leftLine = new Region();
        Region rightLine = new Region();
        leftLine.getStyleClass().add("separator-line");
        rightLine.getStyleClass().add("separator-line");
        HBox.setHgrow(leftLine, Priority.ALWAYS);
        HBox.setHgrow(rightLine, Priority.ALWAYS);
        Label separatorText = new Label("HOẶC");
        separatorText.getStyleClass().add("separator-label");
        HBox separator = new HBox(12, leftLine, separatorText, rightLine);
        separator.setAlignment(Pos.CENTER);

        modePrompt.getStyleClass().add("register-prompt");
        HBox registerRow = new HBox(4, modePrompt, modeButton);
        registerRow.setAlignment(Pos.CENTER);

        status.getStyleClass().add("status-label");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setMinHeight(18);
        status.setAlignment(Pos.CENTER);

        VBox card = new VBox(12, userBox, passwordBox, confirmPasswordBox,
                status, primaryButton, separator, registerRow);
        card.getStyleClass().add("login-card");
        card.setAlignment(Pos.CENTER);
        card.setPrefWidth(420);
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        VBox shell = new VBox(14, branding, card);
        shell.getStyleClass().add("login-shell");
        shell.setAlignment(Pos.CENTER);
        shell.setPadding(new Insets(28, 20, 24, 20));
        shell.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane photoRoot = UiSupport.photoRoot("/images/img7.png", shell, 0.2, 0, 68);
        StackPane.setAlignment(shell, Pos.TOP_CENTER);
        root = photoRoot;

        primaryButton.setDefaultButton(true);
        primaryButton.setOnAction(event -> submit(authHandler));
        modeButton.setOnAction(event -> {
            if (submitting)
                return;
            isRegisterMode = !isRegisterMode;
            updateMode();
        });
        updateMode();
    }

    public Parent root() {
        return root;
    }

    public boolean isSubmitting() {
        return submitting;
    }

    public void completeRequest() {
        setSubmitting(false);
    }

    public void showError(String message) {
        setSubmitting(false);
        setStatus(message, "status-error");
    }

    public void showSuccess(String message) {
        setSubmitting(false);
        setStatus(message, "status-success");
    }

    private void submit(AuthHandler authHandler) {
        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        if (username.isEmpty()) {
            showValidationError("Vui lòng nhập tên đăng nhập.");
            usernameField.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            showValidationError("Vui lòng nhập mật khẩu.");
            passwordField.requestFocus();
            return;
        }
        if (isRegisterMode && !password.equals(confirmPasswordField.getText())) {
            showValidationError("Mật khẩu xác nhận không khớp.");
            confirmPasswordField.requestFocus();
            return;
        }

        setSubmitting(true);
        setStatus(isRegisterMode ? "Đang đăng ký..." : "Đang đăng nhập...", "status-pending");
        authHandler.authenticate(isRegisterMode, username, password);
    }

    private void updateMode() {
        confirmPasswordField.clear();
        confirmPasswordBox.setManaged(isRegisterMode);
        confirmPasswordBox.setVisible(isRegisterMode);
        primaryButton.setText(isRegisterMode ? "TẠO TÀI KHOẢN" : "ĐĂNG NHẬP");
        modePrompt.setText(isRegisterMode ? "Đã có tài khoản?" : "Bạn chưa có tài khoản?");
        modeButton.setText(isRegisterMode ? "Đăng nhập ngay" : "Đăng ký ngay");
        clearStatus();
    }

    private void showValidationError(String message) {
        setStatus(message, "status-error");
    }

    private void setSubmitting(boolean value) {
        submitting = value;
        primaryButton.setDisable(value);
        modeButton.setDisable(value);
    }

    private void clearStatus() {
        status.setText("");
        status.getStyleClass().setAll("status-label");
    }

    private void setStatus(String message, String stateClass) {
        status.setText(message == null ? "" : message);
        status.getStyleClass().setAll("status-label", stateClass);
    }
}
