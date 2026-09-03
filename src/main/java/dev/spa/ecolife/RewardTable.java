package dev.spa.ecolife;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** config.yml の rewards を読んだ、1日目〜31日目の報酬表。 */
final class RewardTable {

    /** カレンダーの最大マス数。31日ある月の皆勤でここまで届く。 */
    static final int MAX_DAY = 31;

    private final Map<Integer, List<ItemStack>> byDay;

    private RewardTable(Map<Integer, List<ItemStack>> byDay) {
        this.byDay = byDay;
    }

    static RewardTable load(JavaPlugin plugin, ConfigurationSection section) {
        Map<Integer, List<ItemStack>> byDay = new HashMap<>();
        if (section == null) {
            plugin.getLogger().warning("config.yml に rewards がありません。報酬を配れません。");
            return new RewardTable(byDay);
        }

        for (String key : section.getKeys(false)) {
            int day;
            try {
                day = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("rewards の " + key + " は日付として読めません。読み飛ばします。");
                continue;
            }
            if (day < 1 || day > MAX_DAY) {
                plugin.getLogger().warning("rewards の " + day + "日目は1〜" + MAX_DAY + "の外です。読み飛ばします。");
                continue;
            }

            List<ItemStack> stacks = new ArrayList<>();
            for (Map<?, ?> entry : section.getMapList(key)) {
                ItemStack stack = toStack(plugin, day, entry);
                if (stack != null) {
                    stacks.add(stack);
                }
            }
            if (!stacks.isEmpty()) {
                byDay.put(day, stacks);
            }
        }

        for (int day = 1; day <= MAX_DAY; day++) {
            if (!byDay.containsKey(day)) {
                plugin.getLogger().warning(day + "日目の報酬が設定されていません。その日は何も配りません。");
            }
        }
        return new RewardTable(byDay);
    }

    private static ItemStack toStack(JavaPlugin plugin, int day, Map<?, ?> entry) {
        Object rawMaterial = entry.get("material");
        if (rawMaterial == null) {
            plugin.getLogger().warning(day + "日目の報酬に material がありません。読み飛ばします。");
            return null;
        }
        Material material = Material.matchMaterial(String.valueOf(rawMaterial));
        if (material == null || !material.isItem()) {
            plugin.getLogger().warning(day + "日目の " + rawMaterial + " はアイテムとして扱えません。読み飛ばします。");
            return null;
        }
        int amount = 1;
        Object rawAmount = entry.get("amount");
        if (rawAmount instanceof Number number) {
            amount = Math.max(1, number.intValue());
        }
        return new ItemStack(material, amount);
    }

    /** その日のマスの報酬。渡すたびに複製を返すので、呼び出し側が変えても表は壊れない。 */
    List<ItemStack> forDay(int day) {
        List<ItemStack> stacks = byDay.get(day);
        if (stacks == null) {
            return List.of();
        }
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copies.add(stack.clone());
        }
        return copies;
    }

    int configuredDays() {
        return byDay.size();
    }
}
