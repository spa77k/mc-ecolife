package dev.spa.ecolife;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** 隔離Paperで14マス目の実商品と受け取り保留を確認する。 */
public final class PaperDailyAdminShopProbe extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                verify();
                getLogger().info("DAILY_ADMINSHOP_PROBE_PASS");
            } catch (Throwable e) {
                getLogger().log(java.util.logging.Level.SEVERE, "DAILY_ADMINSHOP_PROBE_FAIL", e);
            } finally {
                Bukkit.shutdown();
            }
        }, 60);
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private void verify() throws Exception {
        Plugin eco = Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
        check(eco != null && eco.isEnabled(), "EcoLifeAssist enabled");
        Plugin shop = Bukkit.getPluginManager().getPlugin("AdminShop");
        boolean missing = Boolean.getBoolean("probe.no-adminshop");
        check(missing || shop != null && shop.isEnabled(), "AdminShop enabled");

        Object config = field(eco, "bonusConfig");
        Method todayMethod = config.getClass().getDeclaredMethod("today");
        todayMethod.setAccessible(true);
        LocalDate today = (LocalDate) todayMethod.invoke(config);
        UUID uuid = UUID.nameUUIDFromBytes("DailyAdminShopProbe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Inventory storage = Bukkit.createInventory(null, 36);
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "addItem" -> storage.addItem((ItemStack[]) args[0]);
                    case "getContents", "getStorageContents" -> storage.getContents();
                    default -> null;
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "DailyAdminShopProbe";
                    case "getInventory" -> inventory;
                    case "getWorld" -> Bukkit.getWorlds().getFirst();
                    case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                    case "isOnline", "isValid", "hasPermission" -> true;
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "DailyAdminShopProbe";
                    default -> null;
                });

        ClassLoader loader = eco.getClass().getClassLoader();
        Class<?> recordType = loader.loadClass("dev.spa.ecolife.BonusRecord");
        Constructor<?> recordConstructor = recordType.getDeclaredConstructor(String.class, YearMonth.class, int.class,
                LocalDate.class, int.class, int.class, int.class, int.class, LocalDate.class);
        recordConstructor.setAccessible(true);
        Object store = field(eco, "store");
        Method put = store.getClass().getDeclaredMethod("put", UUID.class, recordType);
        Method get = store.getClass().getDeclaredMethod("get", UUID.class);
        put.setAccessible(true);
        get.setAccessible(true);
        put.invoke(store, uuid, recordConstructor.newInstance("DailyAdminShopProbe", YearMonth.from(today), 13,
                today.minusDays(1), 13, 0, 13, 13, today.minusDays(1)));
        Object service = field(eco, "bonuses");
        Method claim = service.getClass().getDeclaredMethod("claim", Player.class);
        claim.setAccessible(true);
        Object result = claim.invoke(service, player);
        Method status = result.getClass().getDeclaredMethod("status");
        status.setAccessible(true);
        Method slotsIn = recordType.getDeclaredMethod("slotsIn", YearMonth.class);
        slotsIn.setAccessible(true);
        int slots = (int) slotsIn.invoke(get.invoke(store, uuid), YearMonth.from(today));

        if (missing) {
            check(status.invoke(result).toString().equals("UNAVAILABLE"), "missing AdminShop defers claim");
            check(slots == 13, "missing AdminShop keeps slot 13");
            check(storage.isEmpty(), "no imitation item given");
            return;
        }
        check(status.invoke(result).toString().equals("CLAIMED"), "slot 14 claimed");
        check(slots == 14, "slot 14 recorded");
        ItemStack item = storage.getItem(0);
        check(item != null && item.getAmount() == 1, "one charm given");
        String perk = item.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey("adminshop", "perk"), PersistentDataType.STRING);
        check("return_charm".equals(perk), "genuine AdminShop return charm");
        Object second = claim.invoke(service, player);
        check(status.invoke(second).toString().equals("ALREADY_CLAIMED"), "cannot claim twice");
        check(storage.getItem(0).getAmount() == 1, "no duplicate charm");

        // 商品設定が消えたときも記録を進めない。
        ((JavaPlugin) shop).getConfig().set("items.return_charm", null);
        ((JavaPlugin) shop).saveConfig();
        Method reload = shop.getClass().getDeclaredMethod("reloadShopConfig");
        reload.setAccessible(true);
        reload.invoke(shop);
        put.invoke(store, uuid, recordConstructor.newInstance("DailyAdminShopProbe", YearMonth.from(today), 13,
                today.minusDays(1), 13, 0, 13, 13, today.minusDays(1)));
        Object noProduct = claim.invoke(service, player);
        check(status.invoke(noProduct).toString().equals("UNAVAILABLE"), "missing product defers claim");
        check((int) slotsIn.invoke(get.invoke(store, uuid), YearMonth.from(today)) == 13,
                "missing product keeps slot 13");
    }
}
