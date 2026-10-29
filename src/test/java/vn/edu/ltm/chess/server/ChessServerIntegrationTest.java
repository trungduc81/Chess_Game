package vn.edu.ltm.chess.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import vn.edu.ltm.chess.client.NetworkClient;
import vn.edu.ltm.chess.config.AppConfig;
import vn.edu.ltm.chess.protocol.*;
import vn.edu.ltm.chess.repository.*;

import java.net.ServerSocket;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** TCP + MySQL thật, tạo và xóa schema tạm; không chạm vào dữ liệu BTL. */
@EnabledIfSystemProperty(named = "chess.integration", matches = "true")
class ChessServerIntegrationTest {
    @Test
    void accountGameQueriesAndAdminKeepTheirProtocolBehavior() throws Exception {
        AppConfig config = AppConfig.load();
        String url = config.databaseUrl();
        int databaseStart = url.indexOf('/', "jdbc:mysql://".length());
        int queryStart = url.indexOf('?', databaseStart);
        String root = url.substring(0, databaseStart + 1);
        String options = queryStart < 0 ? "" : url.substring(queryStart);
        String schema = "btl_chess_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var databaseAdmin = DriverManager.getConnection(root + options,
                config.databaseUser(), config.databasePassword());
             var ddl = databaseAdmin.createStatement()) {
            ddl.executeUpdate("CREATE DATABASE " + schema);
            try {
                int port;
                try (ServerSocket reservation = new ServerSocket(0)) {
                    port = reservation.getLocalPort();
                }
                var repository = new ChessRepository(new Database(root + schema + options,
                        config.databaseUser(), config.databasePassword()));
                try (ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
                    ChessServer server = new ChessServer(port, repository, Duration.ofSeconds(30),
                            Duration.ofSeconds(1), Duration.ofSeconds(15), "test_admin", "test-pass123");
                    Future<?> running = worker.submit(() -> {
                        try {
                            server.start();
                        } catch (Exception exception) {
                            if (!Thread.currentThread().isInterrupted())
                                throw new CompletionException(exception);
                        }
                    });
                    try (Peer alice = connectWhenReady(port, running);
                         Peer bob = new Peer(port);
                         Peer admin = new Peer(port)) {
                        register(alice, "alice");
                        register(bob, "bob");
                        login(alice, "alice");
                        login(bob, "bob");
                        login(admin, "test_admin");
                        try (Peer duplicate = new Peer(port)) {
                            duplicate.send(MessageType.LOGIN, credentials("alice"));
                            assertTrue(duplicate.await(MessageType.ERROR).data().path("message").asText()
                                    .contains("máy khác"));
                        }
                        alice.send(MessageType.ADMIN_USERS_REQUEST, JsonProtocol.object());
                        assertTrue(alice.await(MessageType.ERROR).data().path("message").asText().contains("quyền"));

                        alice.send(MessageType.CHALLENGE_SEND, JsonProtocol.object().put("target", "bob"));
                        String challenge = bob.await(MessageType.CHALLENGE_RECEIVED).data().path("challengeId").asText();
                        bob.send(MessageType.CHALLENGE_RESPONSE,
                                JsonProtocol.object().put("challengeId", challenge).put("accept", true));
                        assertEquals("BLACK", alice.await(MessageType.GAME_STARTED).data().path("yourColor").asText());
                        assertEquals("WHITE", bob.await(MessageType.GAME_STARTED).data().path("yourColor").asText());
                        bob.send(MessageType.LEGAL_MOVES_REQUEST, JsonProtocol.object().put("from", "e2"));
                        assertTrue(bob.await(MessageType.LEGAL_MOVES_RESPONSE).data().path("destinations").toString().contains("e4"));
                        bob.send(MessageType.MOVE, JsonProtocol.object().put("from", "e2").put("to", "e4"));
                        assertEquals(1, alice.await(MessageType.GAME_STATE).data().path("moves").size());
                        bob.await(MessageType.GAME_STATE);
                        bob.send(MessageType.MOVE, JsonProtocol.object().put("from", "d2").put("to", "d4"));
                        bob.await(MessageType.MOVE_REJECTED);
                        alice.send(MessageType.MOVE, JsonProtocol.object().put("from", "e7").put("to", "e5"));
                        assertEquals(2, bob.await(MessageType.GAME_STATE).data().path("moves").size());
                        alice.await(MessageType.GAME_STATE);
                        alice.send(MessageType.CHAT_SEND, JsonProtocol.object().put("message", "hello"));
                        assertEquals("hello", bob.await(MessageType.CHAT_RECEIVED).data().path("message").asText());
                        alice.send(MessageType.DRAW_OFFER, JsonProtocol.object());
                        bob.await(MessageType.DRAW_RECEIVED);
                        bob.send(MessageType.DRAW_RESPONSE, JsonProtocol.object().put("accept", false));
                        assertFalse(alice.await(MessageType.DRAW_RESULT).data().path("accepted").asBoolean());
                        alice.send(MessageType.DRAW_OFFER, JsonProtocol.object());
                        bob.await(MessageType.DRAW_RECEIVED);
                        bob.send(MessageType.DRAW_RESPONSE, JsonProtocol.object().put("accept", true));
                        var drawn = alice.await(MessageType.GAME_OVER).data();
                        assertEquals("DRAW", drawn.path("result").asText());
                        bob.await(MessageType.GAME_OVER);
                        alice.send(MessageType.REMATCH_RESPONSE, JsonProtocol.object().put("accept", true));
                        bob.send(MessageType.REMATCH_RESPONSE, JsonProtocol.object().put("accept", true));
                        assertEquals("WHITE", alice.await(MessageType.GAME_STARTED).data().path("yourColor").asText());
                        bob.await(MessageType.GAME_STARTED);
                        alice.send(MessageType.RESIGN, JsonProtocol.object());
                        assertEquals("BLACK_WIN", alice.await(MessageType.GAME_OVER).data().path("result").asText());
                        assertEquals(16, bob.await(MessageType.GAME_OVER).data().path("blackEloDelta").asInt());

                        alice.send(MessageType.HISTORY_REQUEST, JsonProtocol.object());
                        assertEquals(2, alice.await(MessageType.HISTORY_RESPONSE).data().size());
                        alice.send(MessageType.MATCH_DETAIL_REQUEST,
                                JsonProtocol.object().put("matchId", drawn.path("matchId").asLong()));
                        var detail = alice.await(MessageType.MATCH_DETAIL_RESPONSE).data();
                        assertEquals(2, detail.path("moves").size());
                        assertTrue(detail.path("pgn").asText().contains("e4"));
                        alice.send(MessageType.RANKING_REQUEST, JsonProtocol.object());
                        assertEquals("bob", alice.await(MessageType.RANKING_RESPONSE).data().get(0).path("username").asText());
                        admin.send(MessageType.ADMIN_DASHBOARD_REQUEST, JsonProtocol.object());
                        assertEquals(2, admin.await(MessageType.ADMIN_DASHBOARD_RESPONSE).data().path("totalMatches").asInt());
                        var filter = JsonProtocol.object();
                        filter.putArray("sorts").addObject().put("field", "MOVE_COUNT").put("direction", "ASC");
                        admin.send(MessageType.ADMIN_MATCHES_REQUEST, filter);
                        var matches = admin.await(MessageType.ADMIN_MATCHES_RESPONSE).data();
                        assertEquals(0, matches.get(0).path("moveCount").asInt());
                        assertEquals(2, matches.get(1).path("moveCount").asInt());
                        admin.send(MessageType.ADMIN_KICK_USER, JsonProtocol.object().put("username", "bob"));
                        admin.await(MessageType.ADMIN_ACTION_RESULT);
                        bob.await(MessageType.DISCONNECTED);
                        admin.send(MessageType.ADMIN_LOCK_USER,
                                JsonProtocol.object().put("username", "alice").put("reason", "integration test"));
                        admin.await(MessageType.ADMIN_ACTION_RESULT);
                        alice.await(MessageType.DISCONNECTED);
                        try (Peer retry = new Peer(port)) {
                            retry.send(MessageType.LOGIN, credentials("alice"));
                            assertTrue(retry.await(MessageType.ERROR).data().path("message").asText().contains("bị khóa"));
                            admin.send(MessageType.ADMIN_UNLOCK_USER, JsonProtocol.object().put("username", "alice"));
                            admin.await(MessageType.ADMIN_ACTION_RESULT);
                            login(retry, "alice");
                        }
                        admin.send(MessageType.ADMIN_USERS_REQUEST, JsonProtocol.object());
                        assertEquals(3, admin.await(MessageType.ADMIN_USERS_RESPONSE).data().size());
                    } finally {
                        server.close();
                        running.cancel(true);
                    }
                }
            } finally {
                // schema được tạo từ UUID ngay trong test này, không dùng tên CSDL từ .env.
                ddl.executeUpdate("DROP DATABASE " + schema);
            }
        }
    }

    private static Peer connectWhenReady(int port, Future<?> running) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (running.isDone()) running.get();
            try { return new Peer(port); }
            catch (java.io.IOException ignored) { Thread.sleep(30); }
        }
        throw new AssertionError("Server did not start");
    }

    private static com.fasterxml.jackson.databind.JsonNode credentials(String username) {
        return JsonProtocol.object().put("username", username).put("password", "test-pass123");
    }

    private static void register(Peer peer, String username) throws Exception {
        peer.send(MessageType.REGISTER, credentials(username));
        peer.await(MessageType.SUCCESS);
    }

    private static void login(Peer peer, String username) throws Exception {
        peer.send(MessageType.LOGIN, credentials(username));
        assertEquals(username, peer.await(MessageType.LOGIN_SUCCESS).data().path("username").asText());
    }

    private static final class Peer implements AutoCloseable {
        private final BlockingQueue<NetworkMessage> inbox = new LinkedBlockingQueue<>();
        private final List<NetworkMessage> pending = new ArrayList<>();
        private final NetworkClient client;

        Peer(int port) throws java.io.IOException {
            client = new NetworkClient("127.0.0.1", port, inbox::add);
        }

        void send(MessageType type, com.fasterxml.jackson.databind.JsonNode data) {
            client.send(NetworkMessage.of(type, data));
        }

        NetworkMessage await(MessageType type) throws Exception {
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                NetworkMessage message = iterator.next();
                if (message.type() == type) { iterator.remove(); return message; }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                NetworkMessage message = inbox.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (message == null) break;
                if (message.type() == type) return message;
                if (message.type() == MessageType.ERROR && type != MessageType.ERROR)
                    fail("Server error: " + message.data());
                pending.add(message);
            }
            throw new AssertionError("Missing response: " + type);
        }

        @Override public void close() throws java.io.IOException { client.close(); }
    }
}
