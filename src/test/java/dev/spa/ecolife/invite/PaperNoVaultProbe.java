package dev.spa.ecolife.invite;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperNoVaultProbe extends JavaPlugin {
    @Override
    public void onEnable() {
        Bukkit.getScheduler()
                .runTaskLater(
                        this,
                        () -> {
                            try {
                                JavaPlugin plugin =
                                        (JavaPlugin)
                                                Bukkit.getPluginManager()
                                                        .getPlugin("EcoLifeAssist");
                                if (plugin == null || !plugin.isEnabled())
                                    throw new AssertionError(
                                            "EcoLifeAssist must start without Vault");
                                plugin.getCommand("invite")
                                        .execute(
                                                Bukkit.getConsoleSender(),
                                                "invite",
                                                new String[] {"admin", "status"});
                                plugin.getCommand("ecolife")
                                        .execute(
                                                Bukkit.getConsoleSender(),
                                                "ecolife",
                                                new String[] {"reload"});
                                getLogger().info("INVITE_PROBE_PASS");
                            } catch (Throwable e) {
                                getLogger()
                                        .log(
                                                java.util.logging.Level.SEVERE,
                                                "INVITE_PROBE_FAIL",
                                                e);
                            } finally {
                                Bukkit.shutdown();
                            }
                        },
                        40);
    }
}
