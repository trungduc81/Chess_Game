package vn.edu.ltm.chess.rating;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EloCalculatorTest {
    @Test void equalPlayersGainOrLoseSixteenPoints() {
        assertEquals(16, EloCalculator.whiteDelta(1200, 1200, 1.0));
        assertEquals(-16, EloCalculator.whiteDelta(1200, 1200, 0.0));
        assertEquals(0, EloCalculator.whiteDelta(1200, 1200, 0.5));
    }

    @Test void upsetVictoryGainsMoreThanExpectedVictory() {
        int underdogGain = EloCalculator.whiteDelta(1000, 1400, 1.0);
        int favoriteGain = EloCalculator.whiteDelta(1400, 1000, 1.0);
        assertTrue(underdogGain > favoriteGain);
    }
}
