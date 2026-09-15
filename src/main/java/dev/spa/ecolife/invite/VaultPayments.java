package dev.spa.ecolife.invite;

import net.milkbowl.vault.economy.Economy;

import org.bukkit.Bukkit;

import java.util.UUID;

/** Loaded only when Vault is present; keeps optional API types out of the listener. */
final class VaultPayments {
    static boolean available() {
        var r = Bukkit.getServicesManager().getRegistration(Economy.class);
        return r != null && r.getProvider().isEnabled();
    }

    static boolean deposit(UUID id, double amount) {
        var r = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (r == null || !r.getProvider().isEnabled()) return false;
        var response = r.getProvider().depositPlayer(Bukkit.getOfflinePlayer(id), amount);
        return response != null && response.transactionSuccess();
    }
}
