package dev.spa.ecolife;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** ログインボーナスの判定・付与・通知をまとめる。 */
final class BonusService {

    private final EcoLifeAssistPlugin plugin;

    BonusService(EcoLifeAssistPlugin plugin) {
        this.plugin = plugin;
    }

    /** 今日ぶんがまだ残っているか。 */
    boolean canClaim(UUID uuid) {
        BonusConfig config = plugin.bonusConfig();
        if (!config.enabled()) {
            return false;
        }
        LocalDate lastClaim = plugin.store().get(uuid).lastClaim();
        return lastClaim == null || !lastClaim.equals(config.today());
    }

    /**
     * 受け取りを試す。
     *
     * マスは「その月に受け取った回数」で進む。休んでも戻らないが進みもしないので、
     * 休んだ日数ぶんだけ月の後ろのマスに届かなくなる。月が変わると1マス目に戻る。
     */
    ClaimResult claim(Player player) {
        BonusConfig config = plugin.bonusConfig();
        if (!config.enabled()) {
            return ClaimResult.of(ClaimResult.Status.DISABLED);
        }

        LocalDate today = config.today();
        YearMonth month = YearMonth.from(today);
        BonusRecord record = plugin.store().get(player.getUniqueId());
        if (today.equals(record.lastClaim())) {
            return ClaimResult.of(ClaimResult.Status.ALREADY_CLAIMED);
        }

        int slot = record.slotsIn(month) + 1;
        boolean perfect = slot >= month.lengthOfMonth();
        List<ItemStack> rewards = config.rewards().forDay(slot);

        int dropped = rewards.isEmpty() ? 0 : give(player, rewards);
        plugin.store().put(player.getUniqueId(),
                record.claimed(player.getName(), month, slot, today, perfect));
        plugin.store().saveLater();

        if (rewards.isEmpty()) {
            plugin.getLogger().warning(slot + "日目の報酬が設定されていないため、"
                    + player.getName() + " には何も渡していません。");
            return new ClaimResult(ClaimResult.Status.NO_REWARD, slot, List.of(), 0, perfect);
        }
        return new ClaimResult(ClaimResult.Status.CLAIMED, slot, rewards, dropped, perfect);
    }

    /** 持ち物へ入れ、入り切らなかったぶんは足元へ落とす。落としたスタック数を返す。 */
    private int give(Player player, List<ItemStack> rewards) {
        Map<Integer, ItemStack> leftovers =
                player.getInventory().addItem(rewards.toArray(new ItemStack[0]));
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItem(player.getLocation(), leftover);
        }
        return leftovers.size();
    }

    /** 受け取り結果をプレイヤーへ伝える。皆勤のときは設定に応じて全体にも知らせる。 */
    void announce(Player player, ClaimResult result) {
        BonusConfig config = plugin.bonusConfig();
        switch (result.status()) {
            case DISABLED -> player.sendMessage(Text.prefixed("&7いまログインボーナスは配っていません。"));
            case ALREADY_CLAIMED -> {
                player.sendMessage(Text.prefixed("&7今日のぶんは受け取り済みです。次は&f"
                        + remaining(config) + "&7後からです。"));
                sendProgress(player);
            }
            case NO_REWARD -> player.sendMessage(Text.prefixed("&7今日（&f" + result.slot()
                    + "&7マス目）の報酬が設定されていません。運営に知らせてください。"));
            case CLAIMED -> sendClaimed(player, result, config);
        }
    }

    private void sendClaimed(Player player, ClaimResult result, BonusConfig config) {
        YearMonth month = config.currentMonth();
        player.sendMessage(Text.prefixed("&aログインボーナス &f" + month.getMonthValue() + "月の "
                + result.slot() + " マス目 &aを受け取りました。"));
        player.sendMessage(Text.prefixed("&7中身: ").append(itemList(result.given())));

        if (result.dropped() > 0) {
            player.sendMessage(Text.prefixed("&e持ち物がいっぱいだったため、"
                    + result.dropped() + "種類を足元に落としました。拾ってください。"));
        }
        if (result.perfect()) {
            player.sendMessage(Text.prefixed("&e今月は皆勤です。最後のマスまで到達しました。"));
            if (config.broadcastPerfectMonth()) {
                plugin.getServer().broadcast(Text.prefixed("&f" + player.getName() + " &7が"
                        + month.getMonthValue() + "月のログインボーナスを皆勤で埋めました。"));
            }
        } else {
            int reachable = reachableMax(config, plugin.store().get(player.getUniqueId()));
            player.sendMessage(Text.prefixed("&7このまま毎日入れば、今月は &f" + reachable
                    + " &7マス目まで届きます。&8（最後は " + month.lengthOfMonth() + " マス目）"));
        }

        if (config.title()) {
            player.showTitle(Title.title(
                    Text.of("&aログインボーナス"),
                    Text.of("&f" + month.getMonthValue() + "月 " + result.slot() + " マス目"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1800), Duration.ofMillis(500))));
        }
        if (!config.soundKey().isEmpty()) {
            player.playSound(player.getLocation(), config.soundKey(), 0.7F, 1.2F);
        }
    }

    /** /daily と受け取り済みの案内で使う、今月の進み具合。 */
    void sendProgress(Player player) {
        BonusConfig config = plugin.bonusConfig();
        BonusRecord record = plugin.store().get(player.getUniqueId());
        YearMonth month = config.currentMonth();
        int slots = record.slotsIn(month);

        player.sendMessage(Text.prefixed("&f" + month.getMonthValue() + "月&7のカレンダー: &f"
                + slots + " &7/ " + month.lengthOfMonth() + " マス"));

        int next = slots + 1;
        if (next <= month.lengthOfMonth()) {
            List<ItemStack> rewards = config.rewards().forDay(next);
            if (rewards.isEmpty()) {
                player.sendMessage(Text.prefixed("&7次（&f" + next + "&7マス目）の報酬は未設定です。"));
            } else {
                player.sendMessage(Text.prefixed("&7次（&f" + next + "&7マス目）: ").append(itemList(rewards)));
            }
        }
        player.sendMessage(Text.prefixed("&7このまま毎日入れば &f" + reachableMax(config, record)
                + " &7マス目まで届きます。&8（休んだ日はマスが進みません）"));
    }

    /**
     * 今月このまま毎日入った場合に到達できるマス。
     * 今日ぶんが未受け取りなら今日を残りに数える。
     */
    private static int reachableMax(BonusConfig config, BonusRecord record) {
        LocalDate today = config.today();
        YearMonth month = YearMonth.from(today);
        int remainingDays = month.lengthOfMonth() - today.getDayOfMonth();
        if (!today.equals(record.lastClaim())) {
            remainingDays++;
        }
        return Math.min(month.lengthOfMonth(), record.slotsIn(month) + remainingDays);
    }

    /** アイテム名はクライアント側の言語で出す。数量は自分で添える。 */
    private static Component itemList(List<ItemStack> stacks) {
        Component line = Component.empty();
        for (int i = 0; i < stacks.size(); i++) {
            if (i > 0) {
                line = line.append(Text.of("&7, "));
            }
            ItemStack stack = stacks.get(i);
            line = line.append(stack.displayName()).append(Text.of("&f×" + stack.getAmount()));
        }
        return line;
    }

    /** 次の区切りまでの残り時間。 */
    static String remaining(BonusConfig config) {
        Duration duration = config.untilNextDay();
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours > 0) {
            return hours + "時間" + minutes + "分";
        }
        return Math.max(1, minutes) + "分";
    }
}
