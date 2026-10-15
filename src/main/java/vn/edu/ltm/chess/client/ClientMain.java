package vn.edu.ltm.chess.client;

import javafx.application.Application;

/** Lớp trung gian giúp chạy JavaFX ổn định từ Maven và nút Run của VS Code. */
public final class ClientMain {
    private ClientMain() { }

    public static void main(String[] args) {
        Application.launch(ChessClientApp.class, args);
    }
}
