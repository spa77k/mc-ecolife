package dev.spa.ecolife.rtp;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.logging.Level;

/** Vault がない環境でも無料の RTP を使えるよう、経済 API の参照を分離する。 */
final class RtpPayments {
    private RtpPayments() {}

    static boolean available() {
        return economy() != null;
    }

    static boolean canPay(Player player, int amount) {
        Economy economy = economy();
        if (economy == null) return false;
        try {
            return economy.has(player, amount);
        } catch (RuntimeException e) {
            Bukkit.getLogger().log(Level.WARNING, "RTP残高の確認に失敗しました", e);
            return false;
        }
    }

    static boolean withdraw(Player player, int amount) {
        Economy economy = economy();
        if (economy == null) return false;
        try {
            var response = economy.withdrawPlayer(player, amount);
            return response != null && response.transactionSuccess();
        } catch (RuntimeException e) {
            Bukkit.getLogger().log(Level.WARNING, "RTP決済に失敗しました", e);
            return false;
        }
    }

    static boolean refund(Player player, int amount) {
        Economy economy = economy();
        if (economy == null) return false;
        try {
            var response = economy.depositPlayer(player, amount);
            return response != null && response.transactionSuccess();
        } catch (RuntimeException e) {
            Bukkit.getLogger().log(Level.SEVERE, "RTP返金に失敗しました", e);
            return false;
        }
    }

    private static Economy economy() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return null;
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || !registration.getProvider().isEnabled()) return null;
        return registration.getProvider();
    }
}
