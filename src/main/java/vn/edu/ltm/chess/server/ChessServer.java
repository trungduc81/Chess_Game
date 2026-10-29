package vn.edu.ltm.chess.server;

import vn.edu.ltm.chess.protocol.*;
import vn.edu.ltm.chess.repository.ChessRepository;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.*;
import static vn.edu.ltm.chess.server.ServerMessages.*;

/** Một TCP server: quản lý vòng đời Socket và định tuyến message tới service. */
public final class ChessServer implements AutoCloseable {
    private final int port;
    private final ChessRepository repository;
    private final long heartbeatIntervalMillis;
    private final String bootstrapAdminUsername;
    private final String bootstrapAdminPassword;
    private final ExecutorService clientExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final GameService games;
    private final AccountService accounts;
    private final AdminService admin;
    private final MatchQueryService queries;
    private volatile boolean running;
    private ServerSocket serverSocket;

    public ChessServer(int port, ChessRepository repository, Duration moveTimeout,
                       Duration heartbeatInterval, Duration heartbeatTimeout,
                       String bootstrapAdminUsername, String bootstrapAdminPassword) {
        this.port = port;
        this.repository = repository;
        this.heartbeatIntervalMillis = heartbeatInterval.toMillis();
        this.bootstrapAdminUsername = bootstrapAdminUsername == null ? "" : bootstrapAdminUsername;
        this.bootstrapAdminPassword = bootstrapAdminPassword == null ? "" : bootstrapAdminPassword;
        ServerState state = new ServerState();
        ServerMessages messages = new ServerMessages(state, repository);
        games = new GameService(state, repository, messages, clientExecutor, moveTimeout.toMillis());
        accounts = new AccountService(state, repository, messages, games, clientExecutor, heartbeatTimeout.toMillis());
        admin = new AdminService(state, repository, accounts);
        queries = new MatchQueryService(repository);
    }

    public void start() throws Exception {
        repository.initialize();
        repository.bootstrapAdmin(bootstrapAdminUsername, bootstrapAdminPassword);
        serverSocket = new ServerSocket(port);
        running = true;
        scheduler.scheduleAtFixedRate(games::checkTimeouts, 200, 200, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(accounts::heartbeat, heartbeatIntervalMillis,
                heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
        System.out.println("Chess server đang chạy tại 0.0.0.0:" + port);
        while (running) {
            Socket socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            clientExecutor.submit(new ClientConnection(socket, this));
        }
    }

    public void handle(ClientConnection client, NetworkMessage message) {
        try {
            client.markSeen();
            if (message.type() == MessageType.PONG)
                return;
            if (message.type() == MessageType.PING) {
                client.send(NetworkMessage.reply(MessageType.PONG, message.requestId(), JsonProtocol.object()));
                return;
            }
            if (message.type() == MessageType.REGISTER) {
                accounts.register(client, message);
                return;
            }
            if (message.type() == MessageType.LOGIN) {
                accounts.login(client, message);
                return;
            }
            if (!accounts.authenticated(client, message))
                return;
            if ("ADMIN".equals(client.role()) && !AdminService.adminMessage(message.type())
                    && message.type() != MessageType.LOGOUT
                    && message.type() != MessageType.MATCH_DETAIL_REQUEST) {
                error(client, message, "Tài khoản quản trị không tham gia chức năng thi đấu");
                return;
            }

            switch (message.type()) {
                case LOGOUT -> accounts.logout(client);
                case CHALLENGE_SEND -> games.challenge(client, message);
                case CHALLENGE_RESPONSE -> games.challengeResponse(client, message);
                case LEGAL_MOVES_REQUEST -> games.legalMoves(client, message);
                case MOVE -> games.move(client, message);
                case CHAT_SEND -> games.chat(client, message);
                case DRAW_OFFER -> games.drawOffer(client, message);
                case DRAW_RESPONSE -> games.drawResponse(client, message);
                case RESIGN -> games.resign(client, message);
                case EXIT_GAME -> games.exitGame(client, message);
                case REMATCH_RESPONSE -> games.rematch(client, message);
                case HISTORY_REQUEST -> queries.history(client, message);
                case MATCH_DETAIL_REQUEST -> queries.matchDetail(client, message);
                case RANKING_REQUEST -> queries.ranking(client, message);
                case ADMIN_DASHBOARD_REQUEST -> admin.adminDashboard(client, message);
                case ADMIN_USERS_REQUEST -> admin.adminUsers(client, message);
                case ADMIN_LOCK_USER -> admin.adminAccountStatus(client, message, true);
                case ADMIN_UNLOCK_USER -> admin.adminAccountStatus(client, message, false);
                case ADMIN_KICK_USER -> admin.adminKickUser(client, message);
                case ADMIN_MATCHES_REQUEST -> admin.adminMatches(client, message);
                default -> error(client, message, "Loại thông điệp không hợp lệ trong trạng thái hiện tại");
            }
        } catch (Exception exception) {
            exception.printStackTrace();
            error(client, message, "Lỗi server: " + safeMessage(exception));
        }
    }

    public void disconnected(ClientConnection client) {
        accounts.disconnected(client);
    }

    @Override
    public void close() throws Exception {
        running = false;
        scheduler.shutdownNow();
        clientExecutor.shutdownNow();
        if (serverSocket != null)
            serverSocket.close();
    }
}
