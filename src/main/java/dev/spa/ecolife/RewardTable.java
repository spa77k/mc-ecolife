package dev.spa.ecolife;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** 1日目〜31日目の報酬表。config.yml の rewards か、月ごとに抽選したカレンダーから作る。 */
final class RewardTable {

    /** カレンダーの最大マス数。31日ある月の皆勤でここまで届く。 */
    static final int MAX_DAY = 31;

    private final JavaPlugin plugin;
    private final Map<Integer, List<RewardEntry>> byDay;

    RewardTable(JavaPlugin plugin, Map<Integer, List<RewardEntry>> byDay) {
        this.plugin = plugin;
        this.byDay = byDay;
    }

    /** 報酬1つぶん。アイテムは渡すときに作る。 */
    record RewardEntry(Material material, int amount, String adminShopId) {
        ItemStack create(JavaPlugin plugin) {
            if (adminShopId == null) {
                return new ItemStack(material, amount);
            }
            ItemStack item = AdminShopReward.create(plugin, adminShopId);
            item.setAmount(amount);
            return item;
        }

        /** calendars.yml に書き戻すときの形。config.yml の書き方と同じにする。 */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (adminShopId == null) {
                map.put("material", material.name());
            } else {
                map.put("adminshop-item", adminShopId);
            }
            map.put("amount", amount);
            return map;
        }

        /**
         * 1行ぶんの書き方を読む。読めなければ警告を出して null を返す。
         * where は警告に出す場所の説明（例: 「5日目」）。
         */
        static RewardEntry parse(JavaPlugin plugin, String where, Map<?, ?> entry) {
            int amount = 1;
            Object rawAmount = entry.get("amount");
            if (rawAmount instanceof Number number) {
                amount = Math.max(1, number.intValue());
            }
            Object product = entry.get("adminshop-item");
            if (product != null) {
                String id = String.valueOf(product);
                if (!id.matches("[a-z0-9_]+") || entry.containsKey("material")) {
                    plugin.getLogger().warning(where + "の adminshop-item が不正です: " + id);
                    return null;
                }
                return new RewardEntry(null, amount, id);
            }
            Object rawMaterial = entry.get("material");
            if (rawMaterial == null) {
                plugin.getLogger().warning(where + "の報酬に material がありません。読み飛ばします。");
                return null;
            }
            Material material = Material.matchMaterial(String.valueOf(rawMaterial));
            if (material == null || !material.isItem()) {
                plugin.getLogger().warning(where + "の " + rawMaterial + " はアイテムとして扱えません。読み飛ばします。");
                return null;
            }
            return new RewardEntry(material, amount, null);
        }

        static List<RewardEntry> parseAll(JavaPlugin plugin, String where, List<?> entries) {
            List<RewardEntry> parsed = new ArrayList<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map)) {
                    plugin.getLogger().warning(where + "に読めない行があります。読み飛ばします。");
                    continue;
                }
                RewardEntry reward = parse(plugin, where, map);
                if (reward != null) {
                    parsed.add(reward);
                }
            }
            return parsed;
        }
    }

    static final class UnavailableException extends RuntimeException {
        UnavailableException(String message) { super(message); }
    }

    static RewardTable load(JavaPlugin plugin, ConfigurationSection section) {
        Map<Integer, List<RewardEntry>> byDay = new HashMap<>();
        if (section == null) {
            plugin.getLogger().warning("config.yml に rewards がありません。報酬を配れません。");
            return new RewardTable(plugin, byDay);
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

            List<RewardEntry> stacks = RewardEntry.parseAll(plugin, day + "日目", section.getMapList(key));
            if (!stacks.isEmpty()) {
                byDay.put(day, stacks);
            }
        }

        for (int day = 1; day <= MAX_DAY; day++) {
            if (!byDay.containsKey(day)) {
                plugin.getLogger().warning(day + "日目の報酬が設定されていません。その日は何も配りません。");
            }
        }
        return new RewardTable(plugin, byDay);
    }

    /** その日のマスの報酬。渡すたびに作り直すので、呼び出し側が変えても表は壊れない。 */
    List<ItemStack> forDay(int day) {
        List<RewardEntry> stacks = byDay.get(day);
        if (stacks == null) {
            return List.of();
        }
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (RewardEntry stack : stacks) {
            copies.add(stack.create(plugin));
        }
        return copies;
    }

    int configuredDays() {
        return byDay.size();
    }
}
