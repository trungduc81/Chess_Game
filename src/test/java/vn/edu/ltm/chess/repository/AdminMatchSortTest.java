package vn.edu.ltm.chess.repository;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminMatchSortTest {
    @Test
    void defaultOrdersNewestFirstAndBreaksTies() {
        assertEquals("m.ended_at DESC, m.move_count DESC, m.id DESC",
                ChessRepository.matchOrder(List.of()));
    }

    @Test
    void uncheckedCriteriaDoNotRemainInNextQuery() {
        assertEquals("m.move_count ASC, m.id DESC", ChessRepository.matchOrder(
                List.of(new MatchSort(MatchSort.Field.MOVE_COUNT, true))));
    }

    @Test
    void multipleCriteriaKeepTheirPriorityAndIgnoreDuplicates() {
        assertEquals("m.ended_at ASC, m.move_count DESC, m.id ASC", ChessRepository.matchOrder(
                List.of(new MatchSort(MatchSort.Field.TIME, true),
                        new MatchSort(MatchSort.Field.MOVE_COUNT, false),
                        new MatchSort(MatchSort.Field.ID, true),
                        new MatchSort(MatchSort.Field.TIME, false))));
    }
}
