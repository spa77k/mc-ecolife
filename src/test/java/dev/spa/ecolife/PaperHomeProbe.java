package dev.spa.ecolife;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** EcoLifeAssist がホームコマンドを登録しないことを実Paperで確認する。 */
public final class PaperHomeProbe extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                verify();
                getLogger().info("HOME_PROBE_PASS");
            } catch (Throwable e) {
                getLogger().log(java.util.logging.Level.SEVERE, "HOME_PROBE_FAIL", e);
            } finally {
                Bukkit.shutdown();
            }
        }, 60);
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static void verify() {
        Plugin eco = Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
        Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        check(eco != null && eco.isEnabled(), "EcoLifeAssist enabled");
        check(essentials != null && essentials.isEnabled(), "Essentials enabled");
        for (String name : new String[] {"home", "sethome"}) {
            check(eco.getDescription().getCommands().get(name) == null, "EcoLifeAssist descriptor excludes " + name);
            check(Bukkit.getPluginCommand("ecolifeassist:" + name) == null,
                    "EcoLifeAssist does not register " + name);
            PluginCommand command = Bukkit.getPluginCommand("essentials:" + name);
            check(command != null && command.getPlugin() == essentials, "Essentials owns " + name);
        }
    }
}
