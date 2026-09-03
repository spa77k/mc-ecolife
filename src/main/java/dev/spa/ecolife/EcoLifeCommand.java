package dev.spa.ecolife;

import java.util.ArrayList;
import java.util.List;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /ecolife 設定の再読み込みと受け取り状況の確認・取り消し。 */
final class EcoLifeCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("reload", "info", "reset", "notify");
    private static final List<String> NOTIFY_SUB_COMMANDS = List.of("status", "test");

    private final EcoLifeAssistPlugin plugin;

    EcoLifeCommand(EcoLifeAssistPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("ecolife.admin")) {
            sender.sendMessage(Text.prefixed("&cこのコマンドを使う権限がありません。"));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadAll();
                BonusConfig config = plugin.bonusConfig();
                sender.sendMessage(Text.prefixed("&a設定を読み込み直しました。"));
                sender.sendMessage(Text.prefixed("&7ログインボーナス: &f"
                        + (config.enabled() ? "有効" : "無効")
                        + " &7/ 報酬表 &f" + config.rewards().configuredDays() + "&7マス設定済み"
                        + " &7/ 区切り &f" + config.zone() + " の " + config.resetHour() + "時"));
            }
            case "info" -> {
                String name = args.length >= 2 ? args[1]
                        : (sender instanceof Player player ? player.getName() : null);
                if (name == null) {
                    sender.sendMessage(Text.prefixed("&7使い方: /ecolife info <プレイヤー名>"));
                    return true;
                }
                sendInfo(sender, name);
            }
            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage(Text.prefixed("&7使い方: /ecolife reset <プレイヤー名>"));
                    return true;
                }
                reset(sender, args[1]);
            }
            case "notify" -> handleNotify(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Text.prefixed("&7/ecolife reload &f- 設定を読み込み直す"));
        sender.sendMessage(Text.prefixed("&7/ecolife info <名前> &f- 受け取り状況を見る"));
        sender.sendMessage(Text.prefixed("&7/ecolife reset <名前> &f- 受け取り記録を消す"));
        sender.sendMessage(Text.prefixed("&7/ecolife notify status &f- Discord通知の状況を見る"));
        sender.sendMessage(Text.prefixed("&7/ecolife notify test &f- Discordへテスト通知を送る"));
    }

    private void handleNotify(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.prefixed("&7使い方: /ecolife notify <status|test>"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> sendNotifyStatus(sender);
            case "test" -> sendNotifyTest(sender);
            default -> sender.sendMessage(Text.prefixed("&7使い方: /ecolife notify <status|test>"));
        }
    }

    private void sendNotifyStatus(CommandSender sender) {
        NotifyService.Status status = plugin.notifyService().status();
        sender.sendMessage(Text.prefixed("&fDiscord通知の状況"));
        sender.sendMessage(Text.prefixed("&7設定: &f" + (status.enabled() ? "有効" : "無効")
                + " &7/ URL: &f" + (status.urlConfigured() ? "設定済み" : "未設定")
                + " &7/ 稼働: &f" + (status.active() ? "はい" : "いいえ")));
        sender.sendMessage(Text.prefixed("&7送信成功: &f" + status.sent()
                + " &7/ 送信失敗: &f" + status.failed()
                + " &7/ 間引き・あふれでの破棄: &f" + status.dropped()));

        Map<String, String> subscriptions = plugin.notifyBridge().subscriptionStatus();
        if (subscriptions.isEmpty()) {
            sender.sendMessage(Text.prefixed("&7購読中の発生元はありません。"));
            return;
        }
        for (Map.Entry<String, String> entry : subscriptions.entrySet()) {
            sender.sendMessage(Text.prefixed("&7- &f" + entry.getKey() + " &7: " + entry.getValue()));
        }
    }

    private void sendNotifyTest(CommandSender sender) {
        if (plugin.notifyService().sendTest()) {
            sender.sendMessage(Text.prefixed("&aテスト通知をキューに積みました。届かない場合は notify status も確認してください。"));
        } else {
            sender.sendMessage(Text.prefixed("&c送信できませんでした。無効、URL未設定、またはキューが満杯です。"));
        }
    }

    private void sendInfo(CommandSender sender, String name) {
        UUID uuid = resolve(name);
        if (uuid == null) {
            sender.sendMessage(Text.prefixed("&7" + name + " の記録は見つかりませんでした。"));
            return;
        }
        BonusConfig config = plugin.bonusConfig();
        BonusRecord record = plugin.store().get(uuid);
        YearMonth month = config.currentMonth();
        boolean claimedToday = config.today().equals(record.lastClaim());

        sender.sendMessage(Text.prefixed("&f" + (record.name().isBlank() ? name : record.name()) + " &7の受け取り状況"));
        sender.sendMessage(Text.prefixed("&7今月（&f" + month + "&7）: &f" + record.slotsIn(month)
                + " &7/ " + month.lengthOfMonth() + " マス"));
        sender.sendMessage(Text.prefixed("&7今日: &f" + (claimedToday ? "受け取り済み" : "未受け取り")
                + " &7/ 最終受け取り: &f" + (record.lastClaim() == null ? "なし" : record.lastClaim())));
        sender.sendMessage(Text.prefixed("&7通算: &f" + record.totalClaims() + "&7回 / 皆勤した月: &f"
                + record.perfectMonth() + "&7か月"));
    }

    private void reset(CommandSender sender, String name) {
        UUID uuid = resolve(name);
        if (uuid == null) {
            sender.sendMessage(Text.prefixed("&7" + name + " の記録は見つかりませんでした。"));
            return;
        }
        plugin.store().remove(uuid);
        plugin.store().saveNow();
        sender.sendMessage(Text.prefixed("&a" + name + " の受け取り記録を消しました。次回は今月の1マス目から始まります。"));
    }

    /** オンラインの人を先に見て、いなければ記録に残っている名前から探す。 */
    private UUID resolve(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        return plugin.store().findByName(name);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("ecolife.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUB_COMMANDS, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("reset"))) {
            List<String> names = new ArrayList<>();
            plugin.getServer().getOnlinePlayers().forEach(player -> names.add(player.getName()));
            for (String known : plugin.store().knownNames()) {
                if (!names.contains(known)) {
                    names.add(known);
                }
            }
            return filter(names, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("notify")) {
            return filter(NOTIFY_SUB_COMMANDS, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matched.add(candidate);
            }
        }
        return matched;
    }
}
