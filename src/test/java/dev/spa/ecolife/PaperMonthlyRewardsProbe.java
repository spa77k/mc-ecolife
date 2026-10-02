package dev.spa.ecolife;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** 隔離Paperで、月替わりの抽選・保存による固定・受け取り・カレンダーGUI・保存ファイル破損時の保留を確認する。 */
public final class PaperMonthlyRewardsProbe extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                verify();
                getLogger().info("MONTHLY_REWARDS_PROBE_PASS");
            } catch (Throwable e) {
                getLogger().log(java.util.logging.Level.SEVERE, "MONTHLY_REWARDS_PROBE_FAIL", e);
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

    private static Object call(Object owner, String name, Object... args) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    method.setAccessible(true);
                    return method.invoke(owner, args);
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object callStatic(Class<?> type, String name, Object... args) throws Exception {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return method.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private void verify() throws Exception {
        JavaPlugin eco = (JavaPlugin) Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
        check(eco != null && eco.isEnabled(), "EcoLifeAssist enabled");
        Plugin shop = Bukkit.getPluginManager().getPlugin("AdminShop");
        check(shop != null && shop.isEnabled(), "AdminShop enabled");
        ClassLoader loader = eco.getClass().getClassLoader();
        File calendars = new File(eco.getDataFolder(), "calendars.yml");

        LocalDate today = (LocalDate) call(field(eco, "bonusConfig"), "today");
        YearMonth month = YearMonth.from(today);

        // 開始月より前の月は、毎月同じ rewards を使い、抽選も保存もしない。
        eco.getConfig().set("monthly-rewards.start", month.plusMonths(1).toString());
        eco.saveConfig();
        call(eco, "reloadAll");
        Object config = field(eco, "bonusConfig");
        Object service = field(eco, "bonuses");
        check(call(service, "rewardsFor", month) == call(config, "rewards"), "before start uses fixed rewards");
        check(!calendars.exists(), "no calendar before start");

        // 開始月になったら抽選して calendars.yml に保存する。
        eco.getConfig().set("monthly-rewards.start", month.toString());
        eco.saveConfig();
        call(eco, "reloadAll");
        service = field(eco, "bonuses");
        Object table = call(service, "rewardsFor", month);
        check(calendars.exists(), "calendar saved on first use");
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(calendars);
        String base = "months." + month;
        for (int slot = 1; slot <= 31; slot++) {
            check(!saved.getMapList(base + "." + slot).isEmpty(), slot + " has rewards");
            @SuppressWarnings("unchecked")
            List<ItemStack> items = (List<ItemStack>) call(table, "forDay", slot);
            check(!items.isEmpty(), slot + " creates items");
            for (ItemStack item : items) {
                check(item != null && !item.getType().isAir(), slot + " real item");
            }
        }
        checkPool(eco, saved, base, "early");
        checkPool(eco, saved, base, "middle");
        checkPool(eco, saved, base, "late");
        checkPool(eco, saved, base, "final");
        List<Map<?, ?>> last = saved.getMapList(base + ".31");
        check("GOLDEN_APPLE".equals(last.getLast().get("material")), "slot 31 always ends with a golden apple");

        // 候補を書き換えて読み直しても、抽選済みの月は変わらない。
        String before = Files.readString(calendars.toPath());
        eco.getConfig().set("monthly-rewards.pools.early.candidates",
                List.of(List.of(Map.of("material", "DIRT", "amount", 1))));
        eco.saveConfig();
        call(eco, "reloadAll");
        service = field(eco, "bonuses");
        check(Files.readString(calendars.toPath()).equals(before), "saved month not redrawn after reload");
        @SuppressWarnings("unchecked")
        List<ItemStack> first = (List<ItemStack>) call(call(service, "rewardsFor", month), "forDay", 1);
        check(first.getFirst().getType() != Material.DIRT, "edited candidates do not change this month");

        // 受け取りでは、その月のカレンダーの中身を渡す。
        UUID uuid = UUID.nameUUIDFromBytes("MonthlyRewardsProbe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Inventory storage = Bukkit.createInventory(null, 36);
        List<Inventory> opened = new ArrayList<>();
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "addItem" -> storage.addItem((ItemStack[]) args[0]);
                    case "getContents", "getStorageContents" -> storage.getContents();
                    default -> null;
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "MonthlyRewardsProbe";
                    case "getInventory" -> inventory;
                    case "getWorld" -> Bukkit.getWorlds().getFirst();
                    case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                    case "isOnline", "isValid", "hasPermission" -> true;
                    case "openInventory" -> {
                        if (args[0] instanceof Inventory gui) {
                            opened.add(gui);
                        }
                        yield null;
                    }
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "MonthlyRewardsProbe";
                    default -> null;
                });

        Class<?> recordType = loader.loadClass("dev.spa.ecolife.BonusRecord");
        Constructor<?> recordConstructor = recordType.getDeclaredConstructor(String.class, YearMonth.class, int.class,
                LocalDate.class, int.class, int.class, int.class, int.class, LocalDate.class);
        recordConstructor.setAccessible(true);
        Object store = field(eco, "store");
        call(store, "put", uuid, recordConstructor.newInstance("MonthlyRewardsProbe", month, 13,
                today.minusDays(1), 13, 0, 13, 13, today.minusDays(1)));
        Object result = call(service, "claim", player);
        check(call(result, "status").toString().equals("CLAIMED"), "slot 14 claimed");
        @SuppressWarnings("unchecked")
        List<ItemStack> expected = (List<ItemStack>) call(call(service, "rewardsFor", month), "forDay", 14);
        for (ItemStack item : expected) {
            check(storage.containsAtLeast(item, item.getAmount()), "slot 14 gives " + item.getType());
        }

        // GUI: 受け取り済みは緑のガラス、次のマスは光る中身、届かないマスは灰色のガラス。
        Object gui = field(eco.getCommand("daily").getExecutor(), "gui");
        call(gui, "open", player);
        check(opened.size() == 1, "calendar gui opened");
        Inventory view = opened.getFirst();
        Class<?> guiType = loader.loadClass("dev.spa.ecolife.DailyGui");
        for (int slot = 1; slot <= month.lengthOfMonth(); slot++) {
            ItemStack icon = view.getItem((int) callStatic(guiType, "position", slot));
            check(icon != null, slot + " shown");
            if (slot <= 14) {
                check(icon.getType() == Material.LIME_STAINED_GLASS_PANE, slot + " shown as claimed");
            }
        }
        ItemStack next = view.getItem((int) callStatic(guiType, "position", 15));
        check(next.getType() != Material.LIME_STAINED_GLASS_PANE && next.getType() != Material.GRAY_STAINED_GLASS_PANE,
                "next slot shows the reward");
        check(next.getItemMeta().getEnchantmentGlintOverride(), "next slot glows");
        int reachable = Math.min(month.lengthOfMonth(), 14 + month.lengthOfMonth() - today.getDayOfMonth());
        if (reachable < month.lengthOfMonth()) {
            ItemStack far = view.getItem((int) callStatic(guiType, "position", month.lengthOfMonth()));
            check(far.getType() == Material.GRAY_STAINED_GLASS_PANE, "unreachable slot is gray");
        }
        for (int slot = 0; slot < 54; slot++) {
            ItemStack icon = view.getItem(slot);
            if (icon != null) {
                check(icon.getItemMeta().hasDisplayName(), "slot " + slot + " has a label");
            }
        }

        // 保存ファイルが壊れていたら、上書きせずに受け取りを保留する。
        Files.writeString(calendars.toPath(), "months: [broken\n");
        call(eco, "reloadAll");
        service = field(eco, "bonuses");
        call(store, "put", uuid, recordConstructor.newInstance("MonthlyRewardsProbe", month, 13,
                today.minusDays(1), 13, 0, 13, 13, today.minusDays(1)));
        Object held = call(service, "claim", player);
        check(call(held, "status").toString().equals("UNAVAILABLE"), "broken calendar holds the claim");
        check((int) call(call(store, "get", uuid), "slotsIn", month) == 13, "broken calendar keeps slot 13");
        check(Files.readString(calendars.toPath()).equals("months: [broken\n"), "broken calendar not overwritten");
    }

    /** その段階のマスが、どれも段階の候補のどれかで、同じ候補が重なっていないこと。 */
    private static void checkPool(JavaPlugin eco, YamlConfiguration saved, String base, String pool) {
        List<?> candidates = eco.getConfig().getList("monthly-rewards.pools." + pool + ".candidates");
        Set<Object> used = new HashSet<>();
        for (Object raw : eco.getConfig().getList("monthly-rewards.pools." + pool + ".slots")) {
            List<Map<?, ?>> entries = saved.getMapList(base + "." + raw);
            List<Map<?, ?>> drawn = pool.equals("final") && String.valueOf(raw).equals("31")
                    ? entries.subList(0, entries.size() - 1) : entries;
            check(candidates.contains(drawn), pool + " slot " + raw + " comes from its candidates: " + drawn);
            check(used.add(drawn), pool + " slot " + raw + " repeats a candidate");
        }
    }
}
