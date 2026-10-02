package dev.spa.ecolife;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * /daily で開く今月のカレンダー。見るだけで、クリックしても何も起きない。
 * マスを1行7つ（1〜7、8〜14、…）に並べるので、節目の7・14・21・28マス目が各行の右端に来る。
 */
final class DailyGui implements Listener {

    private static final int SIZE = 54;
    private static final int HISTORY_SLOT = 47;
    private static final int STATUS_SLOT = 49;
    private static final int LEGEND_SLOT = 51;

    private final EcoLifeAssistPlugin plugin;

    DailyGui(EcoLifeAssistPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class View implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void open(Player player) {
        BonusConfig config = plugin.bonusConfig();
        BonusRecord record = plugin.store().get(player.getUniqueId());
        YearMonth month = config.currentMonth();
        int claimed = record.slotsIn(month);
        boolean claimedToday = config.today().equals(record.lastClaim());
        int reachable = BonusService.reachableMax(config, record);

        RewardTable table;
        try {
            table = plugin.bonuses().rewardsFor(month);
        } catch (RewardTable.UnavailableException e) {
            plugin.getLogger().warning(month + " のカレンダーを表示できません: " + e.getMessage());
            table = null;
        }

        View view = new View();
        Inventory inventory = Bukkit.createInventory(view, SIZE,
                Text.of("ログインボーナス " + month.getMonthValue() + "月"));
        view.inventory = inventory;
        for (int slot = 1; slot <= month.lengthOfMonth(); slot++) {
            inventory.setItem(position(slot), mark(table, slot, claimed, reachable));
        }
        inventory.setItem(HISTORY_SLOT, history(record));
        inventory.setItem(STATUS_SLOT, status(config, month, claimed, claimedToday, reachable));
        inventory.setItem(LEGEND_SLOT, legend());
        player.openInventory(inventory);
    }

    /** n マス目を置くインベントリ上の位置。左右1列ずつ空けて、1行に7マス並べる。 */
    static int position(int slot) {
        int index = slot - 1;
        return (index / 7) * 9 + 1 + index % 7;
    }

    private ItemStack mark(RewardTable table, int slot, int claimed, int reachable) {
        List<ItemStack> rewards = null;
        if (table != null) {
            try {
                rewards = table.forDay(slot);
            } catch (RewardTable.UnavailableException e) {
                rewards = null;
            }
        }

        ItemStack icon;
        String state;
        boolean glow = false;
        if (slot <= claimed) {
            icon = new ItemStack(Material.LIME_STAINED_GLASS_PANE, slot);
            state = "&a受け取り済み";
        } else if (slot > reachable) {
            icon = new ItemStack(Material.GRAY_STAINED_GLASS_PANE, slot);
            state = "&8今月はここまで届きません";
        } else {
            icon = rewards == null || rewards.isEmpty()
                    ? new ItemStack(Material.BARRIER)
                    : rewards.getFirst().clone();
            if (slot == claimed + 1) {
                glow = true;
                state = "&e次にもらえるのはこれ";
            } else {
                state = "&7あと &f" + (slot - claimed) + " &7回受け取ると届きます";
            }
        }

        List<Component> lore = new ArrayList<>();
        if (rewards == null) {
            lore.add(line("&7中身を確認できません。運営へお知らせください。"));
        } else if (rewards.isEmpty()) {
            lore.add(line("&7報酬は未設定です。"));
        } else {
            for (ItemStack reward : rewards) {
                lore.add(Component.text("・", NamedTextColor.GRAY)
                        .append(name(reward).colorIfAbsent(NamedTextColor.WHITE))
                        .append(Component.text(" ×" + reward.getAmount(), NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        lore.add(Component.empty());
        lore.add(line(state));

        ItemMeta meta = icon.getItemMeta();
        meta.displayName(line((glow ? "&e" : "&f") + slot + "マス目"));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack status(BonusConfig config, YearMonth month, int claimed, boolean claimedToday, int reachable) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("&7受け取ったマス: &f" + claimed + " &7/ " + month.lengthOfMonth()));
        if (!config.enabled()) {
            lore.add(line("&cいまはログインボーナスを配っていません。"));
        } else if (claimedToday) {
            lore.add(line("&7今日のぶんは受け取り済み。次は &f" + BonusService.remaining(config) + "&7後から"));
        } else {
            lore.add(line("&e今日のぶんはまだ受け取っていません。"));
        }
        lore.add(line("&7このまま毎日入れば &f" + reachable + " &7マス目まで届きます。"));
        return item(Material.CLOCK, "&f" + month.getMonthValue() + "月の進み具合", lore);
    }

    private ItemStack history(BonusRecord record) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("&7連続 &f" + record.streak() + "&7日 / 累計 &f" + record.totalClaims() + "&7日"));
        if (record.showsBestStreak()) {
            lore.add(line("&7最長は &f" + record.bestStreak() + "&7日"));
        }
        return item(Material.BOOK, "&fこれまでの記録", lore);
    }

    private ItemStack legend() {
        return item(Material.OAK_SIGN, "&f見かた", List.of(
                line("&e光っているマス&7: 次にもらえるもの"),
                line("&a緑のガラス&7: 受け取り済み"),
                line("&8灰色のガラス&7: 今月はもう届かない"),
                line("&7受け取った回数でマスが進みます。"),
                line("&7休んだ日はマスが進みません。")));
    }

    private static ItemStack item(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(line(name));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** アイテム名や説明文が斜体にならないようにする。 */
    private static Component line(String legacy) {
        return Text.of(legacy).decoration(TextDecoration.ITALIC, false);
    }

    /** AdminShop の商品のように名前が付いていればそれを、なければクライアントの言語の名前を使う。 */
    private static Component name(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.displayName();
        }
        return Component.translatable(stack.translationKey());
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) {
            event.setCancelled(true);
        }
    }
}
