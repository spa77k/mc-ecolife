package dev.spa.ecolife;

import dev.spa.ecolife.RewardTable.RewardEntry;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 月ごとに抽選したカレンダーを calendars.yml に保存する。
 * その月で最初に必要になったときに1回だけ抽選し、以後は保存した中身を使い続ける。
 * 候補を書き換えても、抽選済みの月の中身は変わらない。
 */
final class CalendarStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<YearMonth, Map<Integer, List<RewardEntry>>> months = new TreeMap<>();
    private final SecureRandom random = new SecureRandom();
    /** ファイルが壊れていて読めなかったとき。上書きして中身を失わないよう、抽選も保存もしない。 */
    private String loadError;

    private CalendarStore(JavaPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    static CalendarStore open(JavaPlugin plugin) {
        CalendarStore store = new CalendarStore(plugin, new File(plugin.getDataFolder(), "calendars.yml"));
        store.load();
        return store;
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            loadError = "calendars.yml を読めません: " + e.getMessage();
            plugin.getLogger().severe(loadError + "。直すまで、抽選の月のログインボーナスは保留します。");
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("months");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            YearMonth month;
            try {
                month = YearMonth.parse(key);
            } catch (DateTimeParseException e) {
                plugin.getLogger().warning("calendars.yml の " + key + " は年月として読めません。読み飛ばします。");
                continue;
            }
            ConfigurationSection days = section.getConfigurationSection(key);
            if (days == null) {
                continue;
            }
            Map<Integer, List<RewardEntry>> byDay = new TreeMap<>();
            for (String dayKey : days.getKeys(false)) {
                int day;
                try {
                    day = Integer.parseInt(dayKey);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("calendars.yml の " + key + " にある " + dayKey + " はマスとして読めません。");
                    continue;
                }
                List<RewardEntry> entries = RewardEntry.parseAll(plugin,
                        "calendars.yml の " + key + " " + day + "マス目", days.getMapList(dayKey));
                if (!entries.isEmpty()) {
                    byDay.put(day, entries);
                }
            }
            months.put(month, byDay);
        }
    }

    /** 保存済みならその中身を、まだならその場で抽選して保存してから返す。 */
    RewardTable getOrDraw(YearMonth month, MonthlyRewards rewards) {
        if (loadError != null) {
            throw new RewardTable.UnavailableException(loadError);
        }
        Map<Integer, List<RewardEntry>> byDay = months.get(month);
        if (byDay == null) {
            byDay = rewards.draw(random);
            months.put(month, byDay);
            save();
            plugin.getLogger().info(month + " のログインボーナスを抽選し、calendars.yml に保存しました。");
            for (Map.Entry<Integer, List<RewardEntry>> day : byDay.entrySet()) {
                plugin.getLogger().info("  " + day.getKey() + "マス目: " + describe(day.getValue()));
            }
        }
        return new RewardTable(plugin, byDay);
    }

    private static String describe(List<RewardEntry> entries) {
        List<String> parts = new ArrayList<>();
        for (RewardEntry entry : entries) {
            String name = entry.adminShopId() == null ? entry.material().name() : "adminshop:" + entry.adminShopId();
            parts.add(name + "×" + entry.amount());
        }
        return String.join(", ", parts);
    }

    /** 抽選は月に1回だけなので、その場で書き切る。書けなかった月は再起動で抽選し直しになる。 */
    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "月ごとに抽選したログインボーナスのカレンダー。EcoLifeAssist が書き込む。",
                "抽選済みの月は config.yml の候補を変えてもそのまま使う。"));
        for (Map.Entry<YearMonth, Map<Integer, List<RewardEntry>>> month : months.entrySet()) {
            for (Map.Entry<Integer, List<RewardEntry>> day : month.getValue().entrySet()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (RewardEntry entry : day.getValue()) {
                    rows.add(entry.toMap());
                }
                yaml.set("months." + month.getKey() + "." + day.getKey(), rows);
            }
        }
        try {
            File folder = file.getParentFile();
            if (folder != null && !folder.exists() && !folder.mkdirs()) {
                plugin.getLogger().warning("データフォルダを作れませんでした: " + folder);
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("calendars.yml を保存できませんでした: " + e.getMessage());
        }
    }

    /** 保存済みの月の数。 */
    int savedMonths() {
        return months.size();
    }
}
