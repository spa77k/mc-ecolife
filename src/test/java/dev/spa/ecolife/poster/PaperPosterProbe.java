package dev.spa.ecolife.poster;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** 隔離Paper上のテスト用Player。実クライアントの表示確認ではない。 */
public final class PaperPosterProbe extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { run(); getLogger().info("POSTER_PROBE_PASS"); }
            catch (Throwable e) { getLogger().log(java.util.logging.Level.SEVERE, "POSTER_PROBE_FAIL", e); }
            finally { Bukkit.shutdown(); }
        }, 60);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static final class User {
        Inventory menu;
        Inventory storage = Bukkit.createInventory(null, 36);
        boolean permission = true;
        List<String> messages = new ArrayList<>();
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(), new Class[]{PlayerInventory.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getStorageContents" -> storage.getContents();
                    case "addItem" -> storage.addItem((ItemStack[]) a[0]);
                    default -> null;
                });
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class},
                (p, m, a) -> switch (m.getName()) {
                    case "hasPermission" -> permission;
                    case "getInventory" -> inventory;
                    case "getWorld" -> Bukkit.getWorlds().getFirst();
                    case "openInventory" -> { menu = (Inventory) a[0]; yield null; }
                    case "sendMessage" -> { messages.add(Arrays.toString(a)); yield null; }
                    case "getName" -> "PosterProbe";
                    case "getUniqueId" -> UUID.nameUUIDFromBytes("PosterProbe".getBytes());
                    case "isOnline", "isValid" -> true;
                    case "hashCode" -> 42;
                    case "equals" -> p == a[0];
                    case "toString" -> "PosterProbe";
                    default -> null;
                });
    }
    private void run() throws Exception {
        JavaPlugin plugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
        check(plugin != null && plugin.isEnabled(), "plugin enabled");
        Object service = field(plugin, "posters");
        check(service != null && !(Boolean) field(service, "loading"), "catalog loaded");
        List<?> posters = (List<?>) field(service, "posters");
        if (Boolean.getBoolean("probe.withdrawn")) {
            check(posters.isEmpty(), "withdrawn catalog empty");
            Map<?, ?> rendered = (Map<?, ?>) field(service, "rendered");
            check(rendered.size() == 4, "withdrawn tiles retained");
            for (Object id : rendered.keySet()) {
                var map = Bukkit.getMap((Integer) id);
                check(map != null && map.getRenderers().size() == 1
                        && map.getRenderers().getFirst().getClass().getName().contains("PosterService"), "withdrawn renderer restored");
            }
            return;
        }
        check(posters.size() == 47, "paginated catalog loaded");
        User user = new User();
        plugin.getCommand("poster").execute(user.player, "poster", new String[0]);
        check(user.menu != null && user.menu.getItem(53) != null, "GUI next page");
        Method open = service.getClass().getDeclaredMethod("open", Player.class, int.class); open.setAccessible(true);
        open.invoke(service, user.player, 1);
        check(user.menu.getItem(45) != null && user.menu.getItem(53) == null, "GUI last page");
        user.permission = false; user.menu = null;
        plugin.getCommand("poster").execute(user.player, "poster", new String[0]);
        check(user.menu == null, "permission denial");
        plugin.getCommand("poster").execute(user.player, "poster", new String[]{"reload"});
        check(!(Boolean) field(service, "loading"), "admin denied");
        user.permission = true;
        Method give = Arrays.stream(service.getClass().getDeclaredMethods()).filter(m -> m.getName().equals("give")).findFirst().orElseThrow();
        give.setAccessible(true);
        for (int i = 0; i < 36; i++) user.storage.setItem(i, new ItemStack(Material.STONE, 64));
        give.invoke(service, user.player, posters.getFirst());
        check(((Map<?, ?>) field(service, "rendered")).isEmpty() || Boolean.getBoolean("probe.restart"), "full inventory creates no maps");
        user.storage.clear();
        give.invoke(service, user.player, posters.getFirst());
        List<Integer> ids = new ArrayList<>();
        for (ItemStack item : user.storage.getContents()) if (item != null) {
            check(item.getType() == Material.FILLED_MAP, "received map");
            var map = ((MapMeta) item.getItemMeta()).getMapView();
            check(map != null && map.isLocked() && !map.isTrackingPosition(), "locked map");
            check(map.getRenderers().size() == 1 && map.getRenderers().getFirst().getClass().getName().contains("PosterService"), "restored renderer");
            ids.add(map.getId());
        }
        check(ids.size() == 4, "2x2 tile set");
        Path saved = Path.of("expected-ids.txt");
        if (Boolean.getBoolean("probe.restart")) check(Files.readString(saved).equals(ids.toString()), "IDs survive restart");
        else Files.writeString(saved, ids.toString());
        user.storage.clear(); give.invoke(service, user.player, posters.getFirst());
        check(((Map<?, ?>) field(service, "rendered")).size() == 4, "repeated receipt reuses IDs");
        // 一覧から取り下げてもキャッシュが残ることを直接確認。
        check(Files.exists(plugin.getDataFolder().toPath().resolve("posters/maps.yml")), "registry persisted");
    }
}
