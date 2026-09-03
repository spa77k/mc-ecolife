package dev.spa.ecolife;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** 参加したときに、その日のログインボーナスを自動で渡す。 */
final class JoinListener implements Listener {

    private final EcoLifeAssistPlugin plugin;

    JoinListener(EcoLifeAssistPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.bonusConfig().enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("ecolife.daily")) {
            return;
        }

        // 参加直後は他の案内が重なって流れてしまうため、少し待ってから渡す。
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.bonuses().canClaim(player.getUniqueId())) {
                plugin.bonuses().announce(player, plugin.bonuses().claim(player));
            }
        }, plugin.bonusConfig().claimDelayTicks());
    }
}
