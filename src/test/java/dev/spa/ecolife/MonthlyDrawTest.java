package dev.spa.ecolife;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonthlyDrawTest {

    private static final MonthlyDraw.Pool<String> EARLY =
            new MonthlyDraw.Pool<>("early", List.of(1, 2, 3), List.of("a", "b", "c", "d"));
    private static final MonthlyDraw.Pool<String> LATE =
            new MonthlyDraw.Pool<>("late", List.of(30, 31), List.of("x", "y", "z"));

    @Test
    void eachSlotTakesFromItsOwnPoolWithoutRepeats() {
        for (long seed = 0; seed < 200; seed++) {
            Map<Integer, String> drawn = MonthlyDraw.draw(List.of(EARLY, LATE), new Random(seed));
            assertEquals(Set.of(1, 2, 3, 30, 31), drawn.keySet());
            Set<String> early = new HashSet<>();
            for (int slot : EARLY.slots()) {
                assertTrue(EARLY.candidates().contains(drawn.get(slot)));
                assertTrue(early.add(drawn.get(slot)), "同じ段階で同じ候補が重なった: seed=" + seed);
            }
            for (int slot : LATE.slots()) {
                assertTrue(LATE.candidates().contains(drawn.get(slot)));
            }
            assertTrue(!drawn.get(30).equals(drawn.get(31)), "最後の2マスが重なった: seed=" + seed);
        }
    }

    @Test
    void fewerCandidatesThanSlotsUsesEveryCandidateBeforeRepeating() {
        MonthlyDraw.Pool<String> small = new MonthlyDraw.Pool<>("small", List.of(1, 2, 3, 4, 5), List.of("a", "b"));
        for (long seed = 0; seed < 50; seed++) {
            Map<Integer, String> drawn = MonthlyDraw.draw(List.of(small), new Random(seed));
            assertEquals(5, drawn.size());
            assertEquals(Set.of("a", "b"), Set.of(drawn.get(1), drawn.get(2)));
            assertEquals(Set.of("a", "b"), Set.of(drawn.get(3), drawn.get(4)));
        }
    }

    @Test
    void everyCandidateCanBePicked() {
        // 連番のシードで作った Random は最初の値が偏るため、1つを使い回す。
        Random random = new Random(42);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(MonthlyDraw.draw(List.of(EARLY), random).get(1));
        }
        assertEquals(Set.copyOf(EARLY.candidates()), seen);
    }

    @Test
    void emptyPoolLeavesItsSlotsUnset() {
        MonthlyDraw.Pool<String> empty = new MonthlyDraw.Pool<>("empty", List.of(7), List.of());
        assertEquals(Map.of(), MonthlyDraw.draw(List.of(empty), new Random(1)));
    }
}
