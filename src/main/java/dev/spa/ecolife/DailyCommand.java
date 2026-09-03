package dev.spa.ecolife;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /daily 今月のカレンダーの進み具合を見る。未受け取りが残っていればその場で渡す。 */
final class DailyCommand implements CommandExecutor {

    private final EcoLifeAssistPlugin plugin;

    DailyCommand(EcoLifeAssistPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.prefixed("&7このコマンドはゲーム内から使ってください。"));
            return true;
        }
        if (!player.hasPermission("ecolife.daily")) {
            player.sendMessage(Text.prefixed("&cログインボーナスを受け取る権限がありません。"));
            return true;
        }

        // 普段は参加時に自動で渡すが、受け取り前にコマンドを打った場合はここで渡す。
        if (plugin.bonuses().canClaim(player.getUniqueId())) {
            plugin.bonuses().announce(player, plugin.bonuses().claim(player));
            return true;
        }
        plugin.bonuses().sendProgress(player);
        return true;
    }
}
