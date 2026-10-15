package vn.edu.ltm.chess.repository;

import vn.edu.ltm.chess.config.AppConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
    private final String url;
    private final String user;
    private final String password;

    public Database() {
        this(AppConfig.load());
    }

    public Database(AppConfig config) {
        this(config.databaseUrl(), config.databaseUser(), config.databasePassword());
    }

    public Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    public Connection connection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
}
