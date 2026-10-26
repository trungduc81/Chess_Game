package vn.edu.ltm.chess.server;

import org.junit.jupiter.api.Test;
import vn.edu.ltm.chess.chess.Move;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test void exposesServerCheckStateAfterCheckmate() {
        GameSession game = new GameSession("white", "black");
        assertTrue(game.move("white", new Move("f2", "f3", null)).accepted());
        assertTrue(game.move("black", new Move("e7", "e5", null)).accepted());
        assertTrue(game.move("white", new Move("g2", "g4", null)).accepted());
        GameSession.TurnResult mate = game.move("black", new Move("d8", "h4", null));

        assertTrue(mate.accepted());
        assertTrue(mate.finished());
        assertEquals("Chiếu bí", mate.moveResult().reason());
        assertTrue(game.currentTurnInCheck());
    }

    @Test void checkStateClearsAfterAValidDefense() {
        GameSession game = new GameSession("white", "black");
        assertTrue(game.move("white", new Move("e2", "e4", null)).accepted());
        assertTrue(game.move("black", new Move("f7", "f6", null)).accepted());
        assertTrue(game.move("white", new Move("d1", "h5", null)).accepted());
        assertTrue(game.stateSnapshot().inCheck());

        assertTrue(game.move("black", new Move("g7", "g6", null)).accepted());
        assertEquals(false, game.stateSnapshot().inCheck());
    }

    @Test void decliningAfterOpponentAcceptsRejectsRematch() {
        GameSession game = finishedGame();

        assertTrue(game.voteRematch("white", true));
        assertEquals(null, game.rematchDecision());
        assertTrue(game.voteRematch("black", false));
        assertEquals(false, game.rematchDecision());
    }

    @Test void acceptingAfterOpponentDeclinesCannotRestoreRematch() {
        GameSession game = finishedGame();

        assertTrue(game.voteRematch("white", false));
        assertEquals(false, game.rematchDecision());
        assertTrue(game.voteRematch("black", true));
        assertEquals(false, game.rematchDecision());
    }

    private static GameSession finishedGame() {
        GameSession game = new GameSession("white", "black");
        assertTrue(game.claimFinish());
        game.finish();
        return game;
    }
}
