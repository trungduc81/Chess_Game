package vn.edu.ltm.chess.server;

import vn.edu.ltm.chess.config.AppConfig;
import vn.edu.ltm.chess.repository.ChessRepository;
import vn.edu.ltm.chess.repository.Database;

import java.time.Duration;

public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : config.serverPort();
        ChessServer server = new ChessServer(port, new ChessRepository(new Database(config)),
                Duration.ofSeconds(config.moveTimeoutSeconds()),
                Duration.ofSeconds(config.heartbeatIntervalSeconds()),
                Duration.ofSeconds(config.heartbeatTimeoutSeconds()),
                config.adminUsername(), config.adminPassword());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception ignored) {
            }
        }));
        server.start();
    }
}
