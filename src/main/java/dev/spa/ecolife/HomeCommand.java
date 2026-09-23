package dev.spa.ecolife;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/** 数字のホーム番号を EssentialsX の既存のホーム記録へ対応付ける。 */
final class HomeCommand implements CommandExecutor, TabCompleter {
    private final EcoLifeAssistPlugin plugin;

    HomeCommand(EcoLifeAssistPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.prefixed("&7このコマンドはゲーム内から使ってください。"));
            return true;
        }
        if (!player.hasPermission("ecolife.home")) {
            player.sendMessage(Text.prefixed("&cホームを利用する権限がありません。"));
            return true;
        }

        boolean setting = command.getName().equalsIgnoreCase("sethome");
        String target = homeName(args);
        if (target == null) {
            player.sendMessage(Text.prefixed("&e使い方: /" + command.getName() + " [1|2]"));
            return true;
        }

        Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        PluginCommand delegate = Bukkit.getPluginCommand("essentials:" + command.getName());
        if (essentials == null || !essentials.isEnabled() || delegate == null
                || delegate.getPlugin() != essentials) {
            player.sendMessage(Text.prefixed("&cホーム機能を利用できません。運営へお知らせください。"));
            return true;
        }

        // EssentialsX は /home 単体でホームが2件あると一覧を出す。
        // 指定先が無いときは別のホームへ自動で飛ぶ場合があるので、先に確認する。
        if (!setting) {
            try {
                if (!HomeEssentialsBridge.hasHome(essentials, player, target)) {
                    player.sendMessage(Text.prefixed("&eホーム" + (target.equals("home") ? "1" : "2")
                            + "は未登録です。/sethome" + (target.equals("home") ? "" : " 2") + " で登録できます。"));
                    return true;
                }
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "EssentialsXのホーム情報を確認できません", e);
                player.sendMessage(Text.prefixed("&cホーム情報を確認できません。運営へお知らせください。"));
                return true;
            }
        }

        delegate.execute(player, "essentials:" + command.getName(), new String[]{target});
        return true;
    }

    private static String homeName(String[] args) {
        if (args.length == 0 || args.length == 1 && (args[0].equals("1") || args[0].equalsIgnoreCase("home"))) {
            return "home";
        }
        if (args.length == 1 && (args[0].equals("2") || args[0].equalsIgnoreCase("home2"))) {
            return "home2";
        }
        return null;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        return List.of("1", "2").stream().filter(value -> value.startsWith(args[0])).toList();
    }
}
