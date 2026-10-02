package dev.spa.ecolife;

import dev.spa.ecolife.RewardTable.RewardEntry;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * config.yml の monthly-rewards を読んだ結果。
 * start の月から、毎月のカレンダーを段階ごとの候補から抽選する。それより前の月は rewards を使う。
 */
final class MonthlyRewards {

    private static final MonthlyRewards OFF = new MonthlyRewards(null, List.of(), Map.of());

    private final YearMonth start;
    private final List<MonthlyDraw.Pool<List<RewardEntry>>> pools;
    private final Map<Integer, List<RewardEntry>> always;

    private MonthlyRewards(YearMonth start, List<MonthlyDraw.Pool<List<RewardEntry>>> pools,
                           Map<Integer, List<RewardEntry>> always) {
        this.start = start;
        this.pools = pools;
        this.always = always;
    }

    static MonthlyRewards load(JavaPlugin plugin, ConfigurationSection section) {
        if (section == null) {
            return OFF;
        }
        String rawStart = section.getString("start", "");
        if (rawStart == null || rawStart.isBlank()) {
            return OFF;
        }
        YearMonth start;
        try {
            start = YearMonth.parse(rawStart.trim());
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("monthly-rewards.start の " + rawStart
                    + " は年月（例: 2026-11）として読めません。月替わりの抽選は使わず、rewards を使います。");
            return OFF;
        }

        List<MonthlyDraw.Pool<List<RewardEntry>>> pools = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        ConfigurationSection poolSection = section.getConfigurationSection("pools");
        if (poolSection != null) {
            for (String name : poolSection.getKeys(false)) {
                MonthlyDraw.Pool<List<RewardEntry>> pool = loadPool(plugin, name,
                        poolSection.getConfigurationSection(name), used);
                if (pool != null) {
                    pools.add(pool);
                }
            }
        }

        Map<Integer, List<RewardEntry>> always = new HashMap<>();
        ConfigurationSection alwaysSection = section.getConfigurationSection("always");
        if (alwaysSection != null) {
            for (String key : alwaysSection.getKeys(false)) {
                Integer slot = slot(plugin, "monthly-rewards.always", key);
                if (slot == null) {
                    continue;
                }
                List<RewardEntry> entries = RewardEntry.parseAll(plugin,
                        "monthly-rewards.always の" + slot + "マス目", alwaysSection.getMapList(key));
                if (!entries.isEmpty()) {
                    always.put(slot, entries);
                }
            }
        }

        Set<Integer> missing = new TreeSet<>();
        for (int day = 1; day <= RewardTable.MAX_DAY; day++) {
            if (!used.contains(day) && !always.containsKey(day)) {
                missing.add(day);
            }
        }
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("monthly-rewards のどの段階にも入っていないマスがあります: " + missing
                    + "。そのマスは何も配りません。");
        }
        return new MonthlyRewards(start, List.copyOf(pools), Map.copyOf(always));
    }

    private static MonthlyDraw.Pool<List<RewardEntry>> loadPool(JavaPlugin plugin, String name,
                                                                ConfigurationSection section, Set<Integer> used) {
        String where = "monthly-rewards.pools." + name;
        if (section == null) {
            plugin.getLogger().warning(where + " が読めません。読み飛ばします。");
            return null;
        }

        List<Integer> slots = new ArrayList<>();
        for (Object raw : section.getList("slots", List.of())) {
            Integer slot = slot(plugin, where + ".slots", String.valueOf(raw));
            if (slot == null) {
                continue;
            }
            if (!used.add(slot)) {
                plugin.getLogger().warning(where + " の " + slot + "マス目は、ほかの段階にも入っています。こちらは読み飛ばします。");
                continue;
            }
            slots.add(slot);
        }

        List<List<RewardEntry>> candidates = new ArrayList<>();
        List<?> rawCandidates = section.getList("candidates", List.of());
        for (int i = 0; i < rawCandidates.size(); i++) {
            String label = where + " の" + (i + 1) + "番目の候補";
            if (!(rawCandidates.get(i) instanceof List<?> rawEntries)) {
                plugin.getLogger().warning(label + "は [ { material: ..., amount: ... } ] の形で書いてください。読み飛ばします。");
                continue;
            }
            List<RewardEntry> entries = RewardEntry.parseAll(plugin, label, rawEntries);
            if (!entries.isEmpty()) {
                candidates.add(List.copyOf(entries));
            }
        }

        if (slots.isEmpty() || candidates.isEmpty()) {
            plugin.getLogger().warning(where + " にマスか候補がありません。読み飛ばします。");
            return null;
        }
        if (candidates.size() < slots.size()) {
            plugin.getLogger().warning(where + " は候補（" + candidates.size() + "）がマス（" + slots.size()
                    + "）より少ないため、同じ月に同じ候補が重なることがあります。");
        }
        return new MonthlyDraw.Pool<>(name, List.copyOf(slots), List.copyOf(candidates));
    }

    private static Integer slot(JavaPlugin plugin, String where, String raw) {
        int slot;
        try {
            slot = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            plugin.getLogger().warning(where + " の " + raw + " はマスとして読めません。読み飛ばします。");
            return null;
        }
        if (slot < 1 || slot > RewardTable.MAX_DAY) {
            plugin.getLogger().warning(where + " の " + slot + "マス目は1〜" + RewardTable.MAX_DAY + "の外です。読み飛ばします。");
            return null;
        }
        return slot;
    }

    /** この月を抽選のカレンダーで配るか。 */
    boolean appliesTo(YearMonth month) {
        return start != null && !month.isBefore(start);
    }

    /** 1か月ぶんのカレンダーを新しく抽選する。always の報酬は抽選した中身の後ろに足す。 */
    Map<Integer, List<RewardEntry>> draw(Random random) {
        Map<Integer, List<RewardEntry>> result = new TreeMap<>();
        MonthlyDraw.draw(pools, random).forEach((slot, entries) -> result.put(slot, new ArrayList<>(entries)));
        always.forEach((slot, extra) -> result.computeIfAbsent(slot, key -> new ArrayList<>()).addAll(extra));
        return result;
    }

    /** 未設定なら null。 */
    YearMonth start() {
        return start;
    }

    int poolCount() {
        return pools.size();
    }
}
