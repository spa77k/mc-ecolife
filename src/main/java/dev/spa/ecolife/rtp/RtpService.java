package dev.spa.ecolife.rtp;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** 未読み込みチャンクを非同期で開き、安全地点を確認してから移動する。 */
public final class RtpService {
    private final JavaPlugin plugin;
    private final File cooldownFile;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Request> pending = new HashMap<>();
    private RtpConfig config;

    public RtpService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.cooldownFile = new File(plugin.getDataFolder(), "rtp-cooldowns.yml");
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(cooldownFile);
        long now = System.currentTimeMillis();
        for (String key : saved.getKeys(false)) {
            try {
                long until = saved.getLong(key);
                if (until > now) cooldowns.put(UUID.fromString(key), until);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("不正なRTPクールダウン記録を無視しました: " + key);
            }
        }
        reload();
    }

    public void reload() {
        this.config = RtpConfig.load(plugin);
    }

    public boolean start(Player player, World world, boolean administrative) {
        if (!config.allows(world.getName())) {
            player.sendMessage("§cこのワールドではランダムテレポートを使えません。");
            return false;
        }
        UUID id = player.getUniqueId();
        if (pending.containsKey(id)) {
            player.sendMessage("§eすでに移動先を探しています。");
            return false;
        }
        long remaining = cooldowns.getOrDefault(id, 0L) - System.currentTimeMillis();
        if (!administrative && remaining > 0) {
            player.sendMessage("§e次に使えるまであと " + ((remaining + 999) / 1000) + " 秒です。");
            return false;
        }
        Request request = new Request(player, world, administrative);
        pending.put(id, request);
        long delay = administrative ? 0 : config.delaySeconds() * 20L;
        if (delay > 0) player.sendMessage("§e" + config.delaySeconds() + "秒後に移動先を探します。");
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> search(request), delay);
        return true;
    }

    public void cancel(Player player) {
        pending.remove(player.getUniqueId());
    }

    public void close() {
        pending.clear();
        saveCooldowns();
    }

    private boolean active(Request request) {
        return plugin.isEnabled() && request.player.isOnline()
                && pending.get(request.player.getUniqueId()) == request
                && (request.administrative || request.player.getWorld().equals(request.originWorld));
    }

    private void search(Request request) {
        if (!active(request)) {
            pending.remove(request.player.getUniqueId(), request);
            return;
        }
        if (++request.attempts > config.maxAttempts()) {
            finish(request, "§c安全な移動先が見つかりませんでした。しばらくしてから再試行してください。");
            return;
        }
        int radius = config.maxRadius();
        int x;
        int z;
        do {
            x = config.centerX() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            z = config.centerZ() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
        } while (Math.max(Math.abs(x - config.centerX()), Math.abs(z - config.centerZ())) < config.minRadius());
        // 周囲の安全確認で隣の未読み込みチャンクを同期ロードしないよう、内側に寄せる。
        x = (x & ~15) + Math.max(2, Math.min(13, x & 15));
        z = (z & ~15) + Math.max(2, Math.min(13, z & 15));
        final int targetX = x;
        final int targetZ = z;
        Location column = new Location(request.world, x + 0.5, 64, z + 0.5);
        if (!request.world.getWorldBorder().isInside(column)) {
            later(request);
            return;
        }
        request.world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, error) -> {
            if (!plugin.isEnabled()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!active(request)) {
                    pending.remove(request.player.getUniqueId(), request);
                    return;
                }
                if (error != null || chunk == null) {
                    later(request);
                    return;
                }
                Location safe = findSafe(request.world, targetX, targetZ);
                if (safe == null) {
                    later(request);
                    return;
                }
                safe.setYaw(request.player.getLocation().getYaw());
                safe.setPitch(request.player.getLocation().getPitch());
                request.player.teleportAsync(safe).whenComplete((success, teleportError) -> {
                    if (!plugin.isEnabled()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (pending.get(request.player.getUniqueId()) != request) return;
                        if (teleportError != null || !Boolean.TRUE.equals(success)) {
                            finish(request, "§cテレポートに失敗しました。もう一度お試しください。");
                            return;
                        }
                        if (!request.administrative) {
                            cooldowns.put(request.player.getUniqueId(),
                                    System.currentTimeMillis() + config.cooldownSeconds() * 1000L);
                            saveCooldowns();
                        }
                        finish(request, "§a安全な地点へ移動しました。");
                    });
                });
            });
        });
    }

    private void later(Request request) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> search(request), 1L);
    }

    private Location findSafe(World world, int x, int z) {
        int low = Math.max(config.minY(), world.getMinHeight() + 1);
        int high = Math.min(config.maxY(), world.getMaxHeight() - 3);
        if (world.getEnvironment() == World.Environment.NETHER) {
            high = Math.min(high, config.netherMaxY());
            for (int y = high; y >= low; y--) {
                if (safeAt(world, x, y, z)) return new Location(world, x + 0.5, y + 0.1, z + 0.5);
            }
            return null;
        }
        int y = world.getHighestBlockYAt(x, z) + 1;
        return y >= low && y <= high && safeAt(world, x, y, z)
                ? new Location(world, x + 0.5, y + 0.1, z + 0.5) : null;
    }

    private static boolean safeAt(World world, int x, int y, int z) {
        Block floor = world.getBlockAt(x, y - 1, z);
        Material type = floor.getType();
        if (!floor.isSolid() || Tag.LEAVES.isTagged(type) || type == Material.BEDROCK
                || type == Material.CACTUS || type == Material.MAGMA_BLOCK
                || type == Material.CAMPFIRE || type == Material.SOUL_CAMPFIRE
                || type == Material.POINTED_DRIPSTONE) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block block = world.getBlockAt(x + dx, y + dy, z + dz);
                    Material material = block.getType();
                    if (!block.isPassable() || block.isLiquid()
                            || material == Material.FIRE || material == Material.SOUL_FIRE
                            || material == Material.POWDER_SNOW || material == Material.SWEET_BERRY_BUSH
                            || material == Material.WITHER_ROSE || material == Material.CACTUS
                            || material == Material.POINTED_DRIPSTONE) return false;
                }
            }
        }
        return true;
    }

    private void finish(Request request, String message) {
        pending.remove(request.player.getUniqueId(), request);
        if (request.player.isOnline()) request.player.sendMessage(message);
    }

    private void saveCooldowns() {
        YamlConfiguration yaml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        cooldowns.forEach((id, until) -> yaml.set(id.toString(), until));
        try {
            yaml.save(cooldownFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "RTPクールダウン記録を保存できませんでした", e);
        }
    }

    private static final class Request {
        final Player player;
        final World world;
        final World originWorld;
        final boolean administrative;
        int attempts;

        Request(Player player, World world, boolean administrative) {
            this.player = player;
            this.world = world;
            this.originWorld = player.getWorld();
            this.administrative = administrative;
        }
    }
}
