package dev.spa.ecolife;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/** 隔離Paperで配布アイテムと各メニューを確認する。実クライアント表示は含まない。 */
public final class PaperPhoneProbe extends JavaPlugin {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { run(); getLogger().info("PHONE_PROBE_PASS"); }
            catch (Throwable error) { getLogger().log(java.util.logging.Level.SEVERE, "PHONE_PROBE_FAIL", error); }
            finally { Bukkit.shutdown(); }
        }, 60);
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static final class User {
        Inventory storage = Bukkit.createInventory(null, 36);
        Inventory ender = Bukkit.createInventory(null, 27);
        Inventory menu;
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getContents" -> storage.getContents();
                    case "firstEmpty" -> storage.firstEmpty();
                    case "addItem" -> storage.addItem((ItemStack[]) args[0]);
                    default -> null;
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission", "isOnline" -> true;
                    case "getInventory" -> inventory;
                    case "getEnderChest" -> ender;
                    case "openInventory" -> { menu = (Inventory) args[0]; yield null; }
                    case "getUniqueId" -> UUID.nameUUIDFromBytes("phone-probe".getBytes());
                    case "getName" -> "PhoneProbe";
                    case "hashCode" -> 42;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "PhoneProbe";
                    default -> null;
                });
    }

    @SuppressWarnings("unchecked")
    private void run() throws Exception {
        JavaPlugin plugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
        check(plugin != null && plugin.isEnabled(), "plugin enabled");
        Object service = field(plugin, "phone");
        check(service != null && plugin.getCommand("phone") != null, "phone command registered");
        User user = new User();
        Method give = service.getClass().getDeclaredMethod("giveIfMissing", Player.class, boolean.class);
        give.setAccessible(true);
        give.invoke(service, user.player, false);
        ItemStack phone = user.storage.getItem(0);
        check(phone != null && phone.getType() == Material.CLOCK, "phone distributed");
        check("ecolife:smartphone".equals(phone.getItemMeta().getItemModel().toString()), "item model");
        PlayerInteractEvent interact = new PlayerInteractEvent(user.player, Action.RIGHT_CLICK_AIR, phone,
                null, BlockFace.SELF, EquipmentSlot.HAND);
        Method use = service.getClass().getDeclaredMethod("onUse", PlayerInteractEvent.class);
        use.setAccessible(true);
        use.invoke(service, interact);
        check(interact.isCancelled(), "right click handled");
        give.invoke(service, user.player, false);
        check(user.storage.getItem(1) == null, "no duplicate distribution");
        plugin.getCommand("phone").execute(user.player, "phone", new String[0]);
        check(user.menu != null && user.menu.getItem(13).getType() == Material.GOLD_INGOT, "auction prominent");
        Map<Integer, Consumer<Player>> actions = (Map<Integer, Consumer<Player>>) field(user.menu.getHolder(), "actions");
        for (int slot : new int[]{10, 12, 14, 16}) check(actions.containsKey(slot), "category " + slot);
        actions.get(10).accept(user.player);
        check(user.menu.getItem(13).getType() == Material.PAPER, "trade category has contracts");
        user.storage.clear();
        for (int i = 0; i < 36; i++) user.storage.setItem(i, new ItemStack(Material.STONE, 64));
        give.invoke(service, user.player, false);
        check(user.storage.getItem(0).getType() == Material.STONE, "full inventory unchanged");
    }
}
