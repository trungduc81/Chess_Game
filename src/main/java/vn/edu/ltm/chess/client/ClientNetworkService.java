package vn.edu.ltm.chess.client;

import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.MessageType;
import vn.edu.ltm.chess.protocol.NetworkMessage;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Kết nối/gửi trên một hàng đợi nền; không phụ thuộc JavaFX. */
public final class ClientNetworkService implements AutoCloseable {
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chess-client-writer").factory());
    private final AtomicLong generation = new AtomicLong();
    private final Consumer<Runnable> dispatch;
    private final Consumer<NetworkMessage> receiver;
    private volatile NetworkClient connection;
    private volatile boolean closed;

    public ClientNetworkService(Consumer<Runnable> dispatch, Consumer<NetworkMessage> receiver) {
        this.dispatch = dispatch;
        this.receiver = receiver;
    }

    public boolean isConnected() {
        return connection != null;
    }

    public void authenticate(String host, int port, NetworkMessage request, Consumer<String> onFailure) {
        if (closed) return;
        long ticket = generation.get();
        writer.execute(() -> {
            if (!isCurrent(ticket)) return;
            try {
                NetworkClient active = connection;
                if (active == null) {
                    NetworkClient connected = new NetworkClient(host, port, message -> deliver(ticket, message));
                    connection = connected;
                    if (!isCurrent(ticket)) {
                        connection = null;
                        closeConnection(connected);
                        return;
                    }
                    active = connected;
                }
                active.send(request);
            } catch (Exception exception) {
                dispatch.accept(() -> {
                    if (isCurrent(ticket)) onFailure.accept(exception.getMessage());
                });
            }
        });
    }

    public void send(NetworkMessage message) {
        if (closed) return;
        long ticket = generation.get();
        writer.execute(() -> {
            if (!isCurrent(ticket)) return;
            NetworkClient active = connection;
            if (active == null) {
                deliver(ticket, NetworkMessage.of(MessageType.DISCONNECTED,
                        JsonProtocol.object().put("message", "Chưa kết nối tới máy chủ")));
                return;
            }
            try {
                active.send(message);
            } catch (IllegalStateException ignored) {
                // NetworkClient đã báo mất kết nối qua receiver.
            }
        });
    }

    /** Hủy tác vụ/phản hồi cũ trước khi chuyển về đăng nhập. */
    public void disconnect(boolean logout) {
        generation.incrementAndGet();
        NetworkClient active = connection;
        connection = null;
        writer.execute(() -> {
            if (active == null) return;
            try {
                if (logout) active.send(NetworkMessage.of(MessageType.LOGOUT, JsonProtocol.object()));
            } catch (IllegalStateException ignored) {
            } finally {
                closeConnection(active);
            }
        });
    }

    private void deliver(long ticket, NetworkMessage message) {
        dispatch.accept(() -> {
            if (isCurrent(ticket)) receiver.accept(message);
        });
    }

    private boolean isCurrent(long ticket) {
        return !closed && generation.get() == ticket;
    }

    private static void closeConnection(NetworkClient active) {
        try {
            active.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        disconnect(false);
        writer.shutdown();
    }
}
