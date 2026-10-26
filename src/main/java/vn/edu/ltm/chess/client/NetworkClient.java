package vn.edu.ltm.chess.client;

import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.LimitedLineReader;
import vn.edu.ltm.chess.protocol.MessageType;
import vn.edu.ltm.chess.protocol.NetworkMessage;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Một kết nối TCP lâu dài của JavaFX client tới server. */
public final class NetworkClient implements AutoCloseable {
    private final Socket socket = new Socket();
    private final LimitedLineReader reader;
    private final BufferedWriter writer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Consumer<NetworkMessage> listener = message -> { };
    private volatile String sessionToken;
    private volatile String gameId;

    public NetworkClient(String host, int port) throws IOException {
        try {
            socket.connect(new InetSocketAddress(host, port), 5_000);
        } catch (IOException exception) {
            try { socket.close(); } catch (IOException ignored) { }
            throw new IOException("Không thể kết nối tới " + host + ":" + port
                    + " (" + exception.getMessage() + ")", exception);
        }
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        reader = new LimitedLineReader(socket.getInputStream());
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("chess-client-reader").start(this::readLoop);
    }

    public void listener(Consumer<NetworkMessage> listener) { this.listener = listener; }

    public synchronized void send(NetworkMessage message) {
        if (closed.get()) throw new IllegalStateException("Kết nối đã đóng");
        try {
            NetworkMessage contextual = message.withContext(sessionToken, gameId);
            writer.write(JsonProtocol.encode(contextual));
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            notifyDisconnected("Mất kết nối tới server");
            throw new IllegalStateException("Mất kết nối tới server", exception);
        }
    }

    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                NetworkMessage message = JsonProtocol.decode(line);
                if (message.type() == MessageType.PING) {
                    send(new NetworkMessage(MessageType.PONG, message.requestId(), sessionToken, gameId,
                            JsonProtocol.object().put("clientTime", System.currentTimeMillis())));
                    continue;
                }
                if (message.type() == MessageType.LOGIN_SUCCESS) {
                    sessionToken = message.payload().path("sessionToken").asText(message.sessionToken());
                } else if (message.type() == MessageType.GAME_STARTED) {
                    gameId = message.payload().path("gameId").asText(message.gameId());
                } else if (message.type() == MessageType.REMATCH_STATUS
                        && message.payload().path("closed").asBoolean(false)) {
                    gameId = null;
                }
                listener.accept(message);
            }
            notifyDisconnected("Server đã đóng kết nối");
        } catch (Exception exception) {
            if (!closed.get()) notifyDisconnected("Mất kết nối tới server: " + exception.getMessage());
        }
    }

    private void notifyDisconnected(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        listener.accept(NetworkMessage.of(MessageType.DISCONNECTED,
                JsonProtocol.object().put("message", reason)));
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public void close() throws IOException {
        closed.set(true);
        socket.close();
    }
}
