package vn.edu.ltm.chess.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads configuration from system properties, environment variables and a local .env file. */
public final class AppConfig {
    private final Map<String, String> values;

    private AppConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static AppConfig load() {
        Map<String, String> fileValues = new HashMap<>();
        String customPath = System.getProperty("chess.env", ".env");
        Path envPath = resolveEnvPath(customPath);
        if (Files.isRegularFile(envPath)) {
            try {
                parseEnv(Files.readAllLines(envPath, StandardCharsets.UTF_8), fileValues);
            } catch (IOException exception) {
                throw new IllegalStateException("Không đọc được file cấu hình " + envPath, exception);
            }
        }
        return new AppConfig(fileValues);
    }

    /**
     * Tìm .env theo thư mục chạy trước, sau đó theo vị trí classes/JAR.
     * Cách thứ hai giúp nút Run của VS Code vẫn đọc đúng cấu hình khi workspace
     * đang mở ở thư mục cha của dự án.
     */
    private static Path resolveEnvPath(String configuredPath) {
        Path configured = Path.of(configuredPath);
        if (configured.isAbsolute()) return configured.normalize();

        Path workingCandidate = configured.toAbsolutePath().normalize();
        if (Files.isRegularFile(workingCandidate)) return workingCandidate;

        try {
            Path codeLocation = Path.of(AppConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path current = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            while (current != null) {
                Path candidate = current.resolve(configured).normalize();
                if (Files.isRegularFile(candidate)) return candidate;
                current = current.getParent();
            }
        } catch (Exception ignored) {
            // Trả về đường dẫn theo working directory để thông báo lỗi vẫn rõ ràng.
        }
        return workingCandidate;
    }

    private static void parseEnv(List<String> lines, Map<String, String> target) {
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            target.put(key, value);
        }
    }

    public String value(String key, String defaultValue) {
        String propertyName = key.toLowerCase().replace('_', '.');
        String system = System.getProperty(propertyName);
        if (system != null && !system.isBlank()) return system;
        String environment = System.getenv(key);
        if (environment != null && !environment.isBlank()) return environment;
        return values.getOrDefault(key, defaultValue);
    }

    public int integer(String key, int defaultValue) {
        String value = value(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " phải là số nguyên: " + value, exception);
        }
    }

    public String serverHost() { return value("CHESS_SERVER_HOST", "127.0.0.1"); }
    public int serverPort() { return integer("CHESS_SERVER_PORT", 5555); }
    public String databaseUrl() {
        return value("CHESS_DB_URL", "jdbc:mysql://localhost:3306/btl_chess?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh");
    }
    public String databaseUser() { return value("CHESS_DB_USER", "root"); }
    public String databasePassword() { return value("CHESS_DB_PASSWORD", ""); }
    public int moveTimeoutSeconds() { return integer("CHESS_MOVE_TIMEOUT_SECONDS", 30); }
    public int heartbeatIntervalSeconds() { return integer("CHESS_HEARTBEAT_INTERVAL_SECONDS", 5); }
    public int heartbeatTimeoutSeconds() { return integer("CHESS_HEARTBEAT_TIMEOUT_SECONDS", 15); }
    public String adminUsername() { return value("CHESS_ADMIN_USERNAME", "").trim(); }
    public String adminPassword() { return value("CHESS_ADMIN_PASSWORD", ""); }
}
