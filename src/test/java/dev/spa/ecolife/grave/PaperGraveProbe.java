package dev.spa.ecolife.grave;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** 隔離Paperでお墓の作成・取り出し・期限切れ・保存・目印の掃除を確認する。実クライアントの表示は含まない。 */
public final class PaperGraveProbe extends JavaPlugin {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private static final class User {
        final String name;
        final UUID uuid;
        final boolean admin;
        Location location;
        int exp;
        final List<String> messages = new ArrayList<>();
        final Inventory storage = Bukkit.createInventory(null, 54);
        final PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getContents" -> java.util.Arrays.copyOf(storage.getContents(), 41);
                    case "getSize" -> 41;
                    case "getItem" -> storage.getItem((Integer) args[0]);
                    case "setItem" -> { storage.setItem((Integer) args[0], (ItemStack) args[1]); yield null; }
                    case "addItem" -> addToMain((ItemStack[]) args[0]);
                    default -> null;
                });
        final Player player;

        User(String name, boolean admin, Location location) {
            this.name = name;
            this.uuid = UUID.nameUUIDFromBytes(name.getBytes());
            this.admin = admin;
            this.location = location;
            this.player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "hasPermission" -> admin || !"ecolife.grave.admin".equals(args[0]);
                        case "isOnline" -> true;
                        case "getInventory" -> inventory;
                        case "getLocation" -> location.clone();
                        case "getWorld" -> location.getWorld();
                        case "getUniqueId" -> uuid;
                        case "getName" -> name;
                        case "giveExp" -> { exp += (Integer) args[0]; yield null; }
                        case "sendMessage" -> { messages.add(String.valueOf(args[0])); yield null; }
                        case "hashCode" -> name.hashCode();
                        case "equals" -> proxy == args[0];
                        case "toString" -> name;
                        default -> null;
                    });
        }

        /** 本物の PlayerInventory と同じく、メイン欄（0〜35）にだけ足す。 */
        private java.util.HashMap<Integer, ItemStack> addToMain(ItemStack[] items) {
            java.util.HashMap<Integer, ItemStack> left = new java.util.HashMap<>();
            for (int i = 0; i < items.length; i++) {
                ItemStack item = items[i];
                int slot = -1;
                for (int s = 0; s < 36; s++) if (storage.getItem(s) == null) { slot = s; break; }
                if (slot < 0) left.put(i, item);
                else storage.setItem(slot, item);
            }
            return left;
        }
    }

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                run();
                getLogger().info("GRAVE_PROBE_PASS");
            } catch (Throwable error) {
                getLogger().log(java.util.logging.Level.SEVERE, "GRAVE_PROBE_FAIL", error);
            }
            Bukkit.shutdown();
        }, 60);
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static PlayerDeathEvent death(User user, List<ItemStack> drops, int exp) {
        return new PlayerDeathEvent(user.player, DamageSource.builder(DamageType.GENERIC).build(), drops, exp, (String) null);
    }

    private static List<ItemStack> dropsOf(User user) {
        List<ItemStack> drops = new ArrayList<>();
        for (int slot = 0; slot < 41; slot++) {
            ItemStack item = user.storage.getItem(slot);
            if (item != null) drops.add(item.clone());
        }
        return drops;
    }

    private void run() throws Exception {
        JavaPlugin plugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
        GraveService service = (GraveService) field(plugin, "graves");
        GraveStore store = (GraveStore) field(service, "store");
        World world = Bukkit.getWorlds().get(0);
        int ground = world.getHighestBlockYAt(8, 8) + 1;
        world.getChunkAt(0, 0).load();

        // 作成: 元のスロットと、持ち物以外のドロップを分けて預ける。
        User owner = new User("GraveOwner", false, new Location(world, 8.3, ground + 3, 8.7, 37f, 0f));
        owner.storage.setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        owner.storage.setItem(5, new ItemStack(Material.DIRT, 32));
        owner.storage.setItem(39, new ItemStack(Material.IRON_HELMET));
        List<ItemStack> drops = dropsOf(owner);
        drops.add(new ItemStack(Material.GOLD_INGOT, 3));
        PlayerDeathEvent event = death(owner, drops, 10);
        service.onDeath(event);
        check(event.getDrops().isEmpty(), "drops must move into the grave");
        check(event.getDroppedExp() == 0, "exp must move into the grave");
        check(store.all().size() == 1, "one grave must exist");
        GraveStore.Grave grave = store.all().iterator().next();
        check(grave.y == ground, "grave must fall to the ground: " + grave.y + " vs " + ground);
        check(grave.yaw == 0f, "yaw must snap to 90 degrees: " + grave.yaw);
        check(grave.items.stream().map(GraveStore.Entry::slot).toList().equals(List.of(0, 5, 39, -1)),
                "slots must be kept: " + grave.items.stream().map(GraveStore.Entry::slot).toList());
        check(grave.exp == 10, "exp must be stored");
        check(grave.entities.size() == 3, "three markers must exist");
        Interaction hitbox = null;
        int displays = 0;
        int texts = 0;
        for (UUID uuid : grave.entities) {
            Entity entity = Bukkit.getEntity(uuid);
            check(entity != null && entity.isPersistent(), "marker must be spawned and persistent");
            if (entity instanceof Interaction interaction) hitbox = interaction;
            if (entity instanceof ItemDisplay display) {
                displays++;
                check(new NamespacedKey("ecolife", "grave").equals(display.getItemStack().getItemMeta().getItemModel()),
                        "display must use the grave item model");
            }
            if (entity instanceof TextDisplay text) {
                texts++;
                check(String.valueOf(text.text()).contains("GraveOwner"), "label must show the owner");
            }
        }
        check(hitbox != null && displays == 1 && texts == 1, "marker types");
        check(new File(plugin.getDataFolder(), "graves.yml").isFile(), "graves.yml must be written");
        check(owner.messages.stream().anyMatch(m -> m.contains("/grave")), "owner must be told where the grave is");

        // 保存: 読み直しても中身が同じ。
        GraveStore reloaded = new GraveStore(plugin);
        GraveStore.Grave copy = reloaded.get(grave.id);
        check(copy != null && copy.items.size() == 4 && copy.items.get(0).item().equals(new ItemStack(Material.DIAMOND_SWORD))
                && copy.items.get(3).item().equals(new ItemStack(Material.GOLD_INGOT, 3))
                && copy.entities.equals(grave.entities), "graves.yml must round-trip");

        // 他人は取り出せない（左クリックでも壊れない）。
        User stranger = new User("Stranger", false, owner.location);
        PlayerInteractEntityEvent strangerClick = new PlayerInteractEntityEvent(stranger.player, hitbox, EquipmentSlot.HAND);
        service.onInteract(strangerClick);
        check(strangerClick.isCancelled() && store.get(grave.id) != null, "stranger must not open the grave");
        check(stranger.storage.isEmpty(), "stranger gets nothing");
        EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(stranger.player, hitbox,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.GENERIC).build(), 1.0);
        service.onAttack(hit);
        check(hit.isCancelled() && store.get(grave.id) != null, "attack must be cancelled without opening");

        // オフハンドのクリックは無視する（右クリックは両手ぶん届くため）。
        owner.storage.clear();
        owner.storage.setItem(5, new ItemStack(Material.STONE));
        service.onInteract(new PlayerInteractEntityEvent(owner.player, hitbox, EquipmentSlot.OFF_HAND));
        check(store.get(grave.id) != null, "off hand click must be ignored");

        // 本人: 空いている元のスロットへ戻し、埋まっていれば空きへ入れる。
        service.onInteract(new PlayerInteractEntityEvent(owner.player, hitbox, EquipmentSlot.HAND));
        check(store.get(grave.id) == null, "grave must be removed after opening");
        for (UUID uuid : grave.entities) check(Bukkit.getEntity(uuid) == null, "markers must be removed");
        check(new ItemStack(Material.DIAMOND_SWORD).equals(owner.storage.getItem(0)), "sword back to slot 0");
        check(new ItemStack(Material.IRON_HELMET).equals(owner.storage.getItem(39)), "helmet back to slot 39");
        check(new ItemStack(Material.STONE).equals(owner.storage.getItem(5)), "occupied slot must stay");
        check(owner.storage.containsAtLeast(new ItemStack(Material.DIRT), 32), "dirt must go to a free slot");
        check(owner.storage.containsAtLeast(new ItemStack(Material.GOLD_INGOT), 3), "extra drop must be returned");
        check(owner.exp == 10, "exp must be returned");
        GraveStore again = new GraveStore(plugin);
        check(again.get(grave.id) == null, "opened grave must be removed from graves.yml");

        // 運営は他人のお墓を取り出せる。元のスロットには戻さない。
        User second = new User("SecondOwner", false, new Location(world, 4.5, ground, 4.5));
        second.storage.setItem(3, new ItemStack(Material.APPLE, 5));
        service.onDeath(death(second, dropsOf(second), 0));
        GraveStore.Grave secondGrave = store.all().iterator().next();
        check(secondGrave.exp == 0, "zero exp grave");
        User admin = new User("Admin", true, second.location);
        Interaction secondHitbox = (Interaction) secondGrave.entities.stream().map(Bukkit::getEntity)
                .filter(Interaction.class::isInstance).findFirst().orElseThrow();
        service.onAttack(new EntityDamageByEntityEvent(admin.player, secondHitbox,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.GENERIC).build(), 1.0));
        check(store.get(secondGrave.id) == null && admin.storage.containsAtLeast(new ItemStack(Material.APPLE), 5),
                "admin must open by attack");

        // 期限切れ: 中身と経験値をその場に落とし、目印を消す。
        User third = new User("ThirdOwner", false, new Location(world, 12.5, ground, 12.5));
        third.storage.setItem(1, new ItemStack(Material.EMERALD, 7));
        service.onDeath(death(third, dropsOf(third), 4));
        GraveStore.Grave thirdGrave = store.all().iterator().next();
        var expires = GraveStore.Grave.class.getDeclaredField("expiresAt");
        expires.setAccessible(true);
        expires.setLong(thirdGrave, System.currentTimeMillis() - 1);
        var tick = GraveService.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(service);
        check(store.get(thirdGrave.id) == null, "expired grave must be removed");
        for (UUID uuid : thirdGrave.entities) check(Bukkit.getEntity(uuid) == null, "expired markers must be removed");
        boolean dropped = world.getNearbyEntities(new Location(world, 12.5, ground, 12.5), 2, 2, 2).stream()
                .anyMatch(e -> e instanceof Item item && item.getItemStack().equals(new ItemStack(Material.EMERALD, 7)));
        check(dropped, "expired items must drop at the grave");
        boolean orb = world.getNearbyEntities(new Location(world, 12.5, ground, 12.5), 2, 2, 2).stream()
                .anyMatch(e -> e instanceof org.bukkit.entity.ExperienceOrb);
        check(orb, "expired exp must drop at the grave");

        // keepInventory 中は作らない。
        User keeper = new User("Keeper", false, new Location(world, 2.5, ground, 2.5));
        keeper.storage.setItem(0, new ItemStack(Material.BREAD));
        PlayerDeathEvent keep = death(keeper, dropsOf(keeper), 5);
        keep.setKeepInventory(true);
        service.onDeath(keep);
        check(store.all().isEmpty() && keep.getDrops().size() == 1, "keepInventory must skip graves");

        // 奈落: 真上の一番高いブロックの上に置く。
        Location voidDeath = new Location(world, 8.5, world.getMinHeight() - 20, 8.5);
        Location placed = GraveService.placeFor(voidDeath);
        check(placed.getY() == ground, "void death must use the highest block: " + placed.getY());

        // 目印の掃除と作り直し: 記録のない目印は消し、目印のないお墓は作り直す。
        User fourth = new User("FourthOwner", false, new Location(world, 6.5, ground, 6.5));
        fourth.storage.setItem(2, new ItemStack(Material.COAL, 9));
        service.onDeath(death(fourth, dropsOf(fourth), 0));
        GraveStore.Grave fourthGrave = store.all().iterator().next();
        List<UUID> oldMarkers = List.copyOf(fourthGrave.entities);
        for (UUID uuid : oldMarkers) Bukkit.getEntity(uuid).remove();
        Interaction orphan = world.spawn(new Location(world, 6.5, ground, 6.5), Interaction.class, e ->
                e.getPersistentDataContainer().set(new NamespacedKey(plugin, "grave"), PersistentDataType.STRING,
                        UUID.randomUUID().toString()));
        service.onEntitiesLoad(new EntitiesLoadEvent(world.getChunkAt(0, 0), List.of(orphan)));
        check(!orphan.isValid(), "orphan marker must be removed");
        check(fourthGrave.entities.size() == 3 && !fourthGrave.entities.equals(oldMarkers)
                && fourthGrave.entities.stream().allMatch(uuid -> Bukkit.getEntity(uuid) != null), "markers must be respawned");
        check(new GraveStore(plugin).get(fourthGrave.id).entities.equals(fourthGrave.entities), "respawned markers must be saved");
    }
}
