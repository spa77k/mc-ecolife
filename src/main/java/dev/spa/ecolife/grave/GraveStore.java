package dev.spa.ecolife.grave;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * お墓の中身を graves.yml に読み書きする。中身の正本はこのファイルで、ワールドの表示エンティティは目印にすぎない。
 * 隔離Paperのテスト（別のクラスローダー）から読めるよう public にしている。
 */
public final class GraveStore {

    /** 元のスロット番号つきのアイテム。slot が -1 なら持ち物以外（他プラグインが足したドロップなど）から来たもの。 */
    public record Entry(int slot, ItemStack item) {
    }

    /** 1基のお墓。表示エンティティのUUIDは、作り直したときに差し替える。 */
    public static final class Grave {
        public final UUID id;
        public final UUID owner;
        public final String ownerName;
        public final String world;
        public final double x;
        public final double y;
        public final double z;
        public final float yaw;
        public final long createdAt;
        final long expiresAt;
        public final int exp;
        public final List<Entry> items;
        public final List<UUID> entities = new ArrayList<>();

        Grave(UUID id, UUID owner, String ownerName, String world, double x, double y, double z, float yaw,
              long createdAt, long expiresAt, int exp, List<Entry> items) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.exp = exp;
            this.items = items;
        }

        int chunkX() {
            return (int) Math.floor(x) >> 4;
        }

        int chunkZ() {
            return (int) Math.floor(z) >> 4;
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Grave> graves = new LinkedHashMap<>();

    public GraveStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "graves.yml");
        load();
    }

    public Collection<Grave> all() {
        return graves.values();
    }

    public Grave get(UUID id) {
        return graves.get(id);
    }

    void add(Grave grave) {
        graves.put(grave.id, grave);
        save();
    }

    /** 取り出し・期限切れのとき、中身を渡す前に呼ぶ。二重に渡さないよう、先に記録から消して保存する。 */
    boolean remove(UUID id) {
        if (graves.remove(id) == null) return false;
        save();
        return true;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("graves");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            try {
                List<Entry> items = new ArrayList<>();
                for (Map<?, ?> raw : section.getMapList("items")) {
                    int slot = ((Number) raw.get("slot")).intValue();
                    byte[] data = Base64.getDecoder().decode(String.valueOf(raw.get("data")));
                    items.add(new Entry(slot, ItemStack.deserializeBytes(data)));
                }
                Grave grave = new Grave(UUID.fromString(key), UUID.fromString(section.getString("owner")),
                        section.getString("owner-name", ""), section.getString("world"),
                        section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                        (float) section.getDouble("yaw"), section.getLong("created-at"),
                        section.getLong("expires-at"), section.getInt("exp"), items);
                for (String entity : section.getStringList("entities")) grave.entities.add(UUID.fromString(entity));
                graves.put(grave.id, grave);
            } catch (RuntimeException e) {
                // 読めない記録を消すと中身が失われるため、起動は続けてファイルは書き換えない。
                plugin.getLogger().log(Level.SEVERE, "graves.yml のお墓 " + key + " を読めませんでした。", e);
            }
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("EcoLifeAssist のお墓。中身の正本なので手で編集しない。"));
        for (Grave grave : graves.values()) {
            ConfigurationSection section = yaml.createSection("graves." + grave.id);
            section.set("owner", grave.owner.toString());
            section.set("owner-name", grave.ownerName);
            section.set("world", grave.world);
            section.set("x", grave.x);
            section.set("y", grave.y);
            section.set("z", grave.z);
            section.set("yaw", grave.yaw);
            section.set("created-at", grave.createdAt);
            section.set("expires-at", grave.expiresAt);
            section.set("exp", grave.exp);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Entry entry : grave.items) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("slot", entry.slot());
                map.put("data", Base64.getEncoder().encodeToString(entry.item().serializeAsBytes()));
                items.add(map);
            }
            section.set("items", items);
            section.set("entities", grave.entities.stream().map(UUID::toString).toList());
        }
        try {
            plugin.getDataFolder().mkdirs();
            File temp = new File(file.getPath() + ".tmp");
            yaml.save(temp);
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "graves.yml を保存できませんでした。", e);
        }
    }
}
