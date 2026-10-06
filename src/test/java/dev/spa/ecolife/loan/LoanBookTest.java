package dev.spa.ecolife.loan;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoanBookTest {
    private static final long WEEK = 7L * 86_400_000L;
    private static final long DUE = 14L * 86_400_000L;
    private static final BigDecimal RATE = new BigDecimal("0.10");
    private final UUID id = UUID.randomUUID();

    @Test
    void interestCompoundsPerPeriodFromTheBorrowTime() {
        LoanBook book = new LoanBook();
        book.borrow(id, "A", new BigDecimal("1000"), 0);
        assertTrue(book.accrue(WEEK - 1, RATE, WEEK).isEmpty());
        assertEquals(new BigDecimal("1100.00"), book.accrue(WEEK, RATE, WEEK).getFirst().after());
        assertTrue(book.accrue(WEEK + 5, RATE, WEEK).isEmpty(), "same period is not charged twice");
        // 止まっていた間のぶんもまとめて付く（2週ぶん → 4週目）。
        assertEquals(new BigDecimal("1464.10"), book.accrue(4 * WEEK, RATE, WEEK).getFirst().after());
    }

    @Test
    void extraBorrowingKeepsTheOriginalDeadline() {
        LoanBook book = new LoanBook();
        book.borrow(id, "A", new BigDecimal("1000"), 0);
        book.borrow(id, "A", new BigDecimal("500"), DUE - 1);
        assertEquals(new BigDecimal("1500"), book.owed(id));
        assertFalse(book.overdue(id, DUE - 1, DUE));
        assertTrue(book.overdue(id, DUE, DUE));
    }

    @Test
    void fullRepaymentResetsTheDeadline() {
        LoanBook book = new LoanBook();
        book.borrow(id, "A", new BigDecimal("1000"), 0);
        assertEquals(new BigDecimal("400"), book.reduce(id, new BigDecimal("600")));
        assertEquals(BigDecimal.ZERO, book.reduce(id, new BigDecimal("400")));
        assertNull(book.get(id));
        book.borrow(id, "A", new BigDecimal("100"), DUE + 5);
        assertFalse(book.overdue(id, DUE + 6, DUE));
    }

    @Test
    void garnishTakesTheRateButNeverMoreThanTheDebt() {
        LoanBook book = new LoanBook();
        book.borrow(id, "A", new BigDecimal("100"), 0);
        BigDecimal half = new BigDecimal("0.5");
        assertEquals(new BigDecimal("0.25"), book.garnish(id, new BigDecimal("0.51"), half));
        assertEquals(BigDecimal.ZERO, book.garnish(id, new BigDecimal("-5"), half));
        assertEquals(new BigDecimal("99.75"), book.garnish(id, new BigDecimal("1000"), half));
        assertNull(book.get(id));
        assertEquals(BigDecimal.ZERO, book.garnish(id, new BigDecimal("1000"), half));
    }

    @Test
    void blockedCommandsMatchOneOrTwoWords() {
        LoanConfig config = new LoanConfig(true, RATE, WEEK, DUE, new BigDecimal("0.5"),
                Map.of("default", BigDecimal.ZERO), List.of("pay", "irai create"), Set.of(), true);
        assertTrue(config.blocks("/pay Friend 100"));
        assertTrue(config.blocks("/PAY Friend 100"));
        assertTrue(config.blocks("/irai  create"));
        assertFalse(config.blocks("/irai my"));
        assertFalse(config.blocks("/irai"));
        assertFalse(config.blocks("/loan repay all"));
        assertFalse(config.blocks("/payday"));
    }
}
