package dev.spa.ecolife.loan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 借金の台帳。残高・利息・期限の計算だけを持ち、お金の出し入れと保存は呼び出し側が行う。 */
public final class LoanBook {

    public record Loan(String name, BigDecimal amount, long startedAt, long interestAt) {}

    /** 利息が付いた1人ぶんの結果。 */
    public record Accrued(UUID id, BigDecimal before, BigDecimal after) {}

    private final Map<UUID, Loan> loans = new HashMap<>();

    public synchronized Loan get(UUID id) {
        return loans.get(id);
    }

    public synchronized Map<UUID, Loan> all() {
        return Map.copyOf(loans);
    }

    public synchronized void put(UUID id, Loan loan) {
        if (loan.amount().signum() > 0) loans.put(id, loan);
    }

    public synchronized BigDecimal owed(UUID id) {
        Loan loan = loans.get(id);
        return loan == null ? BigDecimal.ZERO : loan.amount();
    }

    public synchronized boolean overdue(UUID id, long now, long dueMillis) {
        Loan loan = loans.get(id);
        return loan != null && now >= loan.startedAt() + dueMillis;
    }

    /** 借りた時刻から period ごとに複利で増やす。止まっていた間のぶんもまとめて付ける。 */
    public synchronized List<Accrued> accrue(long now, BigDecimal rate, long periodMillis) {
        List<Accrued> changed = new ArrayList<>();
        for (Map.Entry<UUID, Loan> entry : loans.entrySet()) {
            Loan loan = entry.getValue();
            BigDecimal amount = loan.amount();
            long interestAt = loan.interestAt();
            while (now >= interestAt + periodMillis) {
                amount = amount.multiply(BigDecimal.ONE.add(rate)).setScale(2, RoundingMode.CEILING);
                interestAt += periodMillis;
            }
            if (interestAt != loan.interestAt()) {
                entry.setValue(new Loan(loan.name(), amount, loan.startedAt(), interestAt));
                if (amount.compareTo(loan.amount()) != 0) changed.add(new Accrued(entry.getKey(), loan.amount(), amount));
            }
        }
        return changed;
    }

    /** 借金がなければ今を起点に新しく作り、あれば期限と利息の起点を変えずに足す。 */
    public synchronized Loan borrow(UUID id, String name, BigDecimal amount, long now) {
        Loan loan = loans.get(id);
        Loan next = loan == null ? new Loan(name, amount, now, now)
                : new Loan(name, loan.amount().add(amount), loan.startedAt(), loan.interestAt());
        loans.put(id, next);
        return next;
    }

    /** 残高を減らす。返した後の残高を返し、0になったら台帳から消す。 */
    public synchronized BigDecimal reduce(UUID id, BigDecimal amount) {
        Loan loan = loans.get(id);
        if (loan == null) return BigDecimal.ZERO;
        BigDecimal left = loan.amount().subtract(amount);
        if (left.signum() <= 0) {
            loans.remove(id);
            return BigDecimal.ZERO;
        }
        loans.put(id, new Loan(loan.name(), left, loan.startedAt(), loan.interestAt()));
        return left;
    }

    /** 増えた所持金のうち返済へ回す額を決め、そのぶん残高を減らす。回さないときは0。 */
    public synchronized BigDecimal garnish(UUID id, BigDecimal gained, BigDecimal rate) {
        Loan loan = loans.get(id);
        if (loan == null || gained.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal taken = gained.multiply(rate).setScale(2, RoundingMode.DOWN).min(loan.amount());
        if (taken.signum() <= 0) return BigDecimal.ZERO;
        reduce(id, taken);
        return taken;
    }

    public synchronized Loan remove(UUID id) {
        return loans.remove(id);
    }

    public synchronized void rename(UUID id, String name) {
        Loan loan = loans.get(id);
        if (loan != null && !name.equals(loan.name()))
            loans.put(id, new Loan(name, loan.amount(), loan.startedAt(), loan.interestAt()));
    }
}
