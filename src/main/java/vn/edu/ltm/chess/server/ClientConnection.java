package vn.edu.ltm.chess.server;

import vn.edu.ltm.chess.protocol.JsonProtocol;
import vn.edu.ltm.chess.protocol.LimitedLineReader;
import vn.edu.ltm.chess.protocol.NetworkMessage;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class ClientConnection implements Runnable, AutoCloseable {
    private final Socket socket;
    private final ChessServer server;
    private final LimitedLineReader reader;
    private final BufferedWriter writer;
    private volatile String username;
    private volatile String role = "PLAYER";
    private volatile String sessionToken;
    private volatile long lastSeenAt = System.currentTimeMillis();

    public ClientConnection(Socket socket, ChessServer server) throws IOException {
        this.socket = socket;
        this.server = server;
        reader = new LimitedLineReader(socket.getInputStream());
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    @Override public void run() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lastSeenAt = System.currentTimeMillis();
                server.handle(this, JsonProtocol.decode(line));
            }
        } catch (Exception exception) {
            if (!socket.isClosed()) System.err.println("Mất kết nối client: " + exception.getMessage());
        } finally {
            server.disconnected(this);
            try { close(); } catch (Exception ignored) { }
        }
    }

    public synchronized void send(NetworkMessage message) {
        try {
            writer.write(JsonProtocol.encode(message));
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            try { close(); } catch (Exception ignored) { }
        }
    }

    public String username() { return username; }
    public void username(String username) { this.username = username; }
    public String role() { return role; }
    public void role(String role) { this.role = role; }
    public String sessionToken() { return sessionToken; }
    public void sessionToken(String sessionToken) { this.sessionToken = sessionToken; }
    public long lastSeenAt() { return lastSeenAt; }
    public void markSeen() { lastSeenAt = System.currentTimeMillis(); }
    public String remoteAddress() { return socket.getRemoteSocketAddress().toString(); }

    @Override public void close() throws IOException { socket.close(); }
}
