package vn.edu.ltm.chess.client;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.chess.protocol.*;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientNetworkServiceTest {
    @Test
    void connectionAndWritesRunInBackgroundInOrderAndHeartbeatIsAnswered() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
             ExecutorService ui = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(4000);
            CountDownLatch releaseReply = new CountDownLatch(1);
            Future<List<MessageType>> seen = worker.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    List<MessageType> types = new ArrayList<>();
                    types.add(JsonProtocol.decode(reader.readLine()).type());
                    assertTrue(releaseReply.await(3, TimeUnit.SECONDS));
                    write(writer, NetworkMessage.of(MessageType.LOGIN_SUCCESS,
                            JsonProtocol.object().put("sessionToken", "test-token")));
                    write(writer, NetworkMessage.of(MessageType.PING, JsonProtocol.object()));
                    while (types.size() < 4) {
                        var message = JsonProtocol.decode(reader.readLine());
                        assertEquals("test-token", message.sessionToken());
                        types.add(message.type());
                    }
                    return types;
                }
            });
            var loggedIn = new CompletableFuture<String>();
            try (var client = new ClientNetworkService(ui::execute, message -> {
                if (message.type() == MessageType.LOGIN_SUCCESS)
                    loggedIn.complete(Thread.currentThread().getName());
            })) {
                assertTimeout(Duration.ofSeconds(1), () -> client.authenticate("127.0.0.1", server.getLocalPort(),
                        NetworkMessage.of(MessageType.LOGIN, JsonProtocol.object()),
                        error -> loggedIn.completeExceptionally(new IOException(error))));
                assertFalse(loggedIn.isDone(), "Caller must return while server is still waiting");
                releaseReply.countDown();
                assertNotEquals(Thread.currentThread().getName(), loggedIn.get(3, TimeUnit.SECONDS));
                client.send(NetworkMessage.of(MessageType.HISTORY_REQUEST, JsonProtocol.object()));
                client.send(NetworkMessage.of(MessageType.RANKING_REQUEST, JsonProtocol.object()));
                List<MessageType> types = seen.get(4, TimeUnit.SECONDS);
                assertEquals(MessageType.LOGIN, types.getFirst());
                assertTrue(types.contains(MessageType.PONG));
                assertTrue(types.indexOf(MessageType.HISTORY_REQUEST) < types.indexOf(MessageType.RANKING_REQUEST));
            }
        }
    }

    @Test
    void logoutDropsOldUiCallbacksAndClosesConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setSoTimeout(4000);
            BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
            List<NetworkMessage> delivered = new ArrayList<>();
            Future<MessageType> logout = worker.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    reader.readLine();
                    write(writer, NetworkMessage.of(MessageType.LOGIN_SUCCESS,
                            JsonProtocol.object().put("sessionToken", "test-token")));
                    MessageType type = JsonProtocol.decode(reader.readLine()).type();
                    assertNull(reader.readLine());
                    return type;
                }
            });
            try (var client = new ClientNetworkService(callbacks::add, delivered::add)) {
                client.authenticate("127.0.0.1", server.getLocalPort(),
                        NetworkMessage.of(MessageType.LOGIN, JsonProtocol.object()), error -> fail(error));
                Runnable oldReply = callbacks.poll(3, TimeUnit.SECONDS);
                assertNotNull(oldReply);
                client.disconnect(true);
                oldReply.run();
                assertTrue(delivered.isEmpty());
                assertFalse(client.isConnected());
                assertEquals(MessageType.LOGOUT, logout.get(3, TimeUnit.SECONDS));
            }
        }
    }

    private static void write(BufferedWriter writer, NetworkMessage message) throws IOException {
        writer.write(JsonProtocol.encode(message));
        writer.newLine();
        writer.flush();
    }
}
