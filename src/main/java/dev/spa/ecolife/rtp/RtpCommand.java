package dev.spa.ecolife.rtp;

import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /rtp と初回資源入場用の /rtp player <名前> <ワールド>。 */
public final class RtpCommand implements CommandExecutor, TabCompleter {
    private final RtpService service;

    public RtpCommand(RtpService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("使い方: /rtp player <名前> <ワールド>");
                return true;
            }
            if (!player.hasPermission("ecolife.rtp")) {
                player.sendMessage("§cこのコマンドを使う権限がありません。");
                return true;
            }
            service.start(player, player.getWorld(), false);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            if (!(sender instanceof Player player) || !sender.hasPermission("ecolife.rtp.world")) {
                sender.sendMessage("§cこのコマンドを使う権限がありません。");
                return true;
            }
            World world = Bukkit.getWorld(args[1]);
            if (world == null) sender.sendMessage("§cワールドが見つかりません。");
            else service.start(player, world, false);
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("player")) {
            if (!sender.hasPermission("ecolife.rtp.admin")) {
                sender.sendMessage("§cこのコマンドを使う権限がありません。");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[1]);
            World world = Bukkit.getWorld(args[2]);
            if (player == null || world == null) {
                sender.sendMessage("§cプレイヤーまたはワールドが見つかりません。");
                return true;
            }
            if (service.start(player, world, true)) sender.sendMessage("§a移動先の探索を開始しました。");
            return true;
        }
        sender.sendMessage("§e使い方: /rtp、/rtp world <ワールド>、/rtp player <名前> <ワールド>");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("world", "player").stream()
                    .filter(option -> (option.equals("world") && sender.hasPermission("ecolife.rtp.world"))
                            || (option.equals("player") && sender.hasPermission("ecolife.rtp.admin")))
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("player") && sender.hasPermission("ecolife.rtp.admin")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if ((args.length == 2 && args[0].equalsIgnoreCase("world") && sender.hasPermission("ecolife.rtp.world"))
                || (args.length == 3 && args[0].equalsIgnoreCase("player") && sender.hasPermission("ecolife.rtp.admin"))) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }
}
