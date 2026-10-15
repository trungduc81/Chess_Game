package vn.edu.ltm.chess.server;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.chess.chess.Move;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GameSessionConcurrencyTest {
    @Test void twoSimultaneousMessagesCannotCreateTwoWhiteMoves() throws Exception {
        GameSession game = new GameSession("white", "black");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<GameSession.TurnResult> first = executor.submit(() -> { start.await(); return game.move("white", new Move("e2", "e4", null)); });
            Future<GameSession.TurnResult> second = executor.submit(() -> { start.await(); return game.move("white", new Move("d2", "d4", null)); });
            start.countDown();
            List<GameSession.TurnResult> results = List.of(first.get(), second.get());
            assertEquals(1, results.stream().filter(GameSession.TurnResult::accepted).count());
            assertEquals(1, game.moves().size());
        }
    }

    @Test void timeoutCanOnlyBeClaimedOnce() throws Exception {
        GameSession game = new GameSession("white", "black", 1);
        Thread.sleep(5);
        assertNotNull(game.claimTimeout(System.currentTimeMillis()));
        assertEquals(null, game.claimTimeout(System.currentTimeMillis()));
    }
}
