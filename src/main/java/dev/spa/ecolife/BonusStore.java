package dev.spa.ecolife;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** 受け取り記録を data.yml に読み書きする。 */
final class BonusStore {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, BonusRecord> records = new ConcurrentHashMap<>();
    private final AtomicBoolean savePending = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    private BonusStore(JavaPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    static BonusStore open(JavaPlugin plugin) {
        BonusStore store = new BonusStore(plugin, new File(plugin.getDataFolder(), "data.yml"));
        store.load();
        return store;
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("data.yml に UUID でないキー " + key + " があります。読み飛ばします。");
                continue;
            }
            records.put(uuid, new BonusRecord(
                    section.getString("name", ""),
                    parseMonth(section.getString("month")),
                    section.getInt("month-claims", 0),
                    parseDate(section.getString("last-claim")),
                    section.getInt("total-claims", 0),
                    section.getInt("perfect-months", 0)));
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("data.yml の日付 " + raw + " を読めませんでした。未受け取りとして扱います。");
            return null;
        }
    }

    private YearMonth parseMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(raw);
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("data.yml の月 " + raw + " を読めませんでした。今月ぶんは1マス目から数えます。");
            return null;
        }
    }

    BonusRecord get(UUID uuid) {
        return records.getOrDefault(uuid, BonusRecord.EMPTY);
    }

    void put(UUID uuid, BonusRecord record) {
        records.put(uuid, record);
    }

    void remove(UUID uuid) {
        records.remove(uuid);
    }

    int size() {
        return records.size();
    }

    /** 名前から UUID を探す。オフラインの人を /ecolife info で見るために使う。 */
    UUID findByName(String name) {
        for (Map.Entry<UUID, BonusRecord> entry : records.entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** タブ補完に出す、記録が残っている名前の一覧。 */
    List<String> knownNames() {
        List<String> names = new ArrayList<>();
        for (BonusRecord record : records.values()) {
            if (!record.name().isBlank()) {
                names.add(record.name());
            }
        }
        return names;
    }

    /** 受け取りのたびにディスクへ書きに行かないよう、まとめてから非同期で書く。 */
    void saveLater() {
        if (!savePending.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            savePending.set(false);
            saveNow();
        });
    }

    /** 無効化のときなど、その場で書き切る必要があるときに使う。 */
    void saveNow() {
        synchronized (writeLock) {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, BonusRecord> entry : records.entrySet()) {
                String path = "players." + entry.getKey();
                BonusRecord record = entry.getValue();
                yaml.set(path + ".name", record.name());
                yaml.set(path + ".month", record.month() == null ? null : record.month().toString());
                yaml.set(path + ".month-claims", record.monthClaims());
                yaml.set(path + ".last-claim", record.lastClaim() == null ? null : record.lastClaim().toString());
                yaml.set(path + ".total-claims", record.totalClaims());
                yaml.set(path + ".perfect-months", record.perfectMonth());
            }
            try {
                File folder = file.getParentFile();
                if (folder != null && !folder.exists() && !folder.mkdirs()) {
                    plugin.getLogger().warning("データフォルダを作れませんでした: " + folder);
                }
                yaml.save(file);
            } catch (IOException e) {
                plugin.getLogger().severe("data.yml を保存できませんでした: " + e.getMessage());
            }
        }
    }
}
