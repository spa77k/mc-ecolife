package dev.spa.ecolife.rtp;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** 実Paperのワールドとチャンクを使うRTP検証。Playerだけ決定的なテスト用アダプタ。 */
public final class PaperRtpProbe extends JavaPlugin {
    private int teleports;
    private Location location;
    private Player player;
    private RtpService service;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::begin, 60L);
    }

    private void begin() {
        try {
            JavaPlugin eco = (JavaPlugin) Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
            check(eco != null && eco.isEnabled(), "EcoLifeAssist enabled");
            PluginCommand command = Bukkit.getPluginCommand("ecolifeassist:rtp");
            check(command != null && command.getPlugin() == eco, "EcoLifeAssist owns /rtp");
            check(Bukkit.getPluginManager().getPlugin("BetterRTP") == null, "BetterRTP absent");
            var field = eco.getClass().getDeclaredField("rtp");
            field.setAccessible(true);
            service = (RtpService) field.get(eco);
            World world = Bukkit.getWorlds().getFirst();
            verifyHazards(world);
            location = world.getSpawnLocation();
            UUID id = UUID.nameUUIDFromBytes("rtp-test-player".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> "RtpProbe";
                        case "getWorld" -> location.getWorld();
                        case "getLocation" -> location.clone();
                        case "isOnline", "hasPermission" -> true;
                        case "teleportAsync" -> {
                            location = ((Location) args[0]).clone();
                            teleports++;
                            yield CompletableFuture.completedFuture(true);
                        }
                        case "sendMessage" -> null;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> id.hashCode();
                        default -> {
                            Class<?> type = method.getReturnType();
                            yield type == boolean.class ? false : type == int.class ? 0 : null;
                        }
                    });
            if (Boolean.getBoolean("probe.restart")) {
                command.execute(player, "rtp", new String[0]);
                Bukkit.getScheduler().runTaskLater(this, () -> verifyRestart(world), 5L);
            } else {
                command.execute(player, "rtp", new String[0]);
                Bukkit.getScheduler().runTaskLater(this, () -> checkAndSchedule(world), 10L);
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void checkAndSchedule(World world) {
        try {
            check(teleports == 0, "five second delay is applied");
            Bukkit.getScheduler().runTaskLater(this, () -> verifyFirst(world), 300L);
        } catch (Throwable error) { fail(error); }
    }

    private void verifyFirst(World world) {
        try {
            check(teleports == 1, "player teleported once");
            check(location.getWorld() == world, "same world");
            int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
            check(world.getBlockAt(x, y - 1, z).isSolid(), "solid floor");
            check(world.getBlockAt(x, y, z).isPassable(), "passable feet");
            check(world.getBlockAt(x, y + 1, z).isPassable(), "passable head");
            check(new java.io.File(Bukkit.getPluginManager().getPlugin("EcoLifeAssist").getDataFolder(),
                    "rtp-cooldowns.yml").isFile(), "cooldown persisted");
            Bukkit.getPluginCommand("ecolifeassist:rtp").execute(player, "rtp", new String[0]);
            check(teleports == 1, "cooldown blocks repeat");
            check(service.start(player, world, true), "admin bypass accepted");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    check(teleports == 2, "admin bypass teleported");
                    pass();
                } catch (Throwable error) { fail(error); }
            }, 100L);
        } catch (Throwable error) { fail(error); }
    }

    private void verifyRestart(World world) {
        try {
            check(teleports == 0, "cooldown survives restart");
            check(service.start(player, world, true), "admin bypass after restart");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    check(teleports == 1, "admin teleport after restart");
                    pass();
                } catch (Throwable error) { fail(error); }
            }, 100L);
        } catch (Throwable error) { fail(error); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void verifyHazards(World world) throws Exception {
        world.getChunkAt(0, 0);
        int y = world.getHighestBlockYAt(8, 8) + 1;
        var safeAt = RtpService.class.getDeclaredMethod("safeAt", World.class, int.class, int.class, int.class);
        safeAt.setAccessible(true);
        check((boolean) safeAt.invoke(null, world, 8, y, 8), "normal floor accepted");
        var floor = world.getBlockAt(8, y - 1, 8);
        var oldFloor = floor.getBlockData();
        floor.setType(org.bukkit.Material.MAGMA_BLOCK);
        check(!(boolean) safeAt.invoke(null, world, 8, y, 8), "magma floor rejected");
        floor.setBlockData(oldFloor);
        var adjacent = world.getBlockAt(9, y, 8);
        var oldAdjacent = adjacent.getBlockData();
        adjacent.setType(org.bukkit.Material.LAVA);
        check(!(boolean) safeAt.invoke(null, world, 8, y, 8), "adjacent lava rejected");
        adjacent.setBlockData(oldAdjacent);
    }

    private void pass() {
        getLogger().info("RTP_PROBE_PASS");
        Bukkit.shutdown();
    }

    private void fail(Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE, "RTP_PROBE_FAIL", error);
        Bukkit.shutdown();
    }
}
