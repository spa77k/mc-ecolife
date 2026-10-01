package dev.spa.ecolife;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 無人で動き続けるホッパーと、プレイヤー以外の原因で死ぬモブを実Paperに置く。
 * 検出・通知の中身は、テストスクリプトが受け取ったWebhookと automation.db で確かめる。
 */
public final class PaperAutomationProbe extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            World world = Bukkit.getWorlds().get(0);
            // ホッパー：チャンク(10,10)。上のチェストから吸って下のチェストへ流し続ける
            world.addPluginChunkTicket(10, 10, this);
            Block top = world.getBlockAt(165, 70, 165);
            top.setType(Material.CHEST);
            Chest chest = (Chest) top.getState();
            for (int i = 0; i < 27; i++) chest.getBlockInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
            world.getBlockAt(165, 69, 165).setType(Material.HOPPER);
            world.getBlockAt(165, 68, 165).setType(Material.CHEST);
            logPlacement(world.getBlockAt(165, 69, 165));
            // モブ：チャンク(-10,-10)。1秒ごとにプレイヤー以外の原因で倒す
            world.addPluginChunkTicket(-10, -10, this);
            logActivityListeners();
            getLogger().info("AUTOMATION_PROBE_READY");
        }, 40);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            World world = Bukkit.getWorlds().get(0);
            LivingEntity golem = (LivingEntity) world.spawnEntity(
                    new org.bukkit.Location(world, -155.5, 70, -155.5), EntityType.IRON_GOLEM);
            golem.setHealth(0);
        }, 60, 20);
        long seconds = Long.getLong("probe.seconds", 30);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            getLogger().info("AUTOMATION_PROBE_DONE");
            Bukkit.shutdown();
        }, seconds * 20);
    }

    /** 放置判定に使う操作のイベントを、EcoLifeAssist がいくつ購読しているか。 */
    private void logActivityListeners() {
        java.util.List<org.bukkit.event.HandlerList> lists = java.util.List.of(
                org.bukkit.event.player.PlayerJoinEvent.getHandlerList(),
                com.destroystokyo.paper.event.player.PlayerJumpEvent.getHandlerList(),
                org.bukkit.event.player.PlayerToggleSprintEvent.getHandlerList(),
                org.bukkit.event.player.PlayerToggleSneakEvent.getHandlerList(),
                org.bukkit.event.block.BlockBreakEvent.getHandlerList(),
                org.bukkit.event.block.BlockPlaceEvent.getHandlerList(),
                org.bukkit.event.player.PlayerInteractEvent.getHandlerList(),
                org.bukkit.event.entity.EntityDamageByEntityEvent.getHandlerList(),
                org.bukkit.event.inventory.InventoryClickEvent.getHandlerList());
        long found = lists.stream().filter(list -> java.util.Arrays.stream(list.getRegisteredListeners())
                .anyMatch(r -> r.getListener().getClass().getName().equals("dev.spa.ecolife.AutomationWatch"))).count();
        getLogger().info("AUTOMATION_PROBE_ACTIVITY " + found + "/" + lists.size());
    }

    /** CoreProtect があれば、ホッパーを probe_user が置いた記録を残す。 */
    private void logPlacement(Block block) {
        org.bukkit.plugin.Plugin coreProtect = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (coreProtect == null) return;
        try {
            Object api = coreProtect.getClass().getMethod("getAPI").invoke(coreProtect);
            api.getClass().getMethod("logPlacement", String.class, org.bukkit.Location.class,
                    org.bukkit.block.data.BlockData.class).invoke(api, "probe_user", block.getLocation(), block.getBlockData());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
