package dev.spa.ecolife.loan;

import java.math.BigDecimal;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Vault がない環境でも起動できるよう、経済 API の参照を分離する。 */
final class LoanPayments {
    private LoanPayments() {}

    static boolean available() {
        return economy() != null;
    }

    static BigDecimal balance(Player player) {
        return BigDecimal.valueOf(economy().getBalance(player));
    }

    static boolean deposit(Player player, BigDecimal amount) {
        var response = economy().depositPlayer(player, amount.doubleValue());
        return response != null && response.transactionSuccess();
    }

    static boolean withdraw(Player player, BigDecimal amount) {
        var response = economy().withdrawPlayer(player, amount.doubleValue());
        return response != null && response.transactionSuccess();
    }

    private static Economy economy() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return null;
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || !registration.getProvider().isEnabled()) return null;
        return registration.getProvider();
    }
}
