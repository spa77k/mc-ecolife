package dev.spa.ecolife.grave;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 死亡時の持ち物をお墓に預け、本人だけが取り出せるようにする。期限が過ぎたら中身をその場に落とし、
 * お墓がない場合と同じ状態に戻す。
 *
 * 見た目は墓石の ItemDisplay・名前の TextDisplay・クリック判定の Interaction の3体で作る。
 * Geyser は ItemDisplay を統合版へ送らないため、統合版では名前とクリック判定だけが見える。
 */
public final class GraveService implements Listener, CommandExecutor {
    /** 残り時間の表示と期限切れの見回り間隔（ティック）。20秒。 */
    private static final long TICK_PERIOD = 400L;

    private final JavaPlugin plugin;
    private final GraveStore store;
    private final NamespacedKey marker;
    private final NamespacedKey model = new NamespacedKey("ecolife", "grave");
    private final BukkitTask task;
    private boolean enabled;
    private long expireMillis;
    private boolean keepExp;
    private Set<String> excludedWorlds = Set.of();

    public GraveService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new GraveStore(plugin);
        this.marker = new NamespacedKey(plugin, "grave");
        reload();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, TICK_PERIOD);
    }

    public void reload() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("grave");
        enabled = section == null || section.getBoolean("enabled", true);
        expireMillis = Math.max(1, section == null ? 15 : section.getInt("expire-minutes", 15)) * 60_000L;
        keepExp = section == null || section.getBoolean("keep-exp", true);
        excludedWorlds = section == null ? Set.of() : Set.copyOf(section.getStringList("excluded-worlds"));
    }

    public void close() {
        task.cancel();
        store.save();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled || event.getKeepInventory()) return;
        Player player = event.getEntity();
        if (excludedWorlds.contains(player.getWorld().getName())) return;
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack drop : event.getDrops()) if (!isEmpty(drop)) remaining.add(drop);
        int exp = keepExp ? Math.max(0, event.getDroppedExp()) : 0;
        if (remaining.isEmpty() && exp == 0) return;

        // 元のスロットへ戻せるよう、ドロップを死亡直前の持ち物と突き合わせる。
        List<GraveStore.Entry> entries = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (isEmpty(item)) continue;
            for (int i = 0; i < remaining.size(); i++) {
                if (remaining.get(i).equals(item)) {
                    entries.add(new GraveStore.Entry(slot, remaining.remove(i).clone()));
                    break;
                }
            }
        }
        for (ItemStack rest : remaining) entries.add(new GraveStore.Entry(-1, rest.clone()));

        Location at = placeFor(player.getLocation());
        long now = System.currentTimeMillis();
        GraveStore.Grave grave = new GraveStore.Grave(UUID.randomUUID(), player.getUniqueId(), player.getName(),
                at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), snapYaw(player.getLocation().getYaw()),
                now, now + expireMillis, exp, entries);
        spawn(grave);
        store.add(grave);
        event.getDrops().clear();
        if (keepExp) event.setDroppedExp(0);
        player.sendMessage(prefixed("持ち物をお墓に預けました: " + describe(grave) + "。"
                + minutesLeft(grave) + "分以内にお墓を右クリック（統合版はタップ）すると取り出せます。"
                + "過ぎると中身がその場に落ちます。場所は /grave で確認できます。", NamedTextColor.YELLOW));
        plugin.getLogger().info(player.getName() + " のお墓を作りました: " + describe(grave)
                + " アイテム" + entries.size() + "種 経験値" + exp);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        UUID id = graveId(event.getRightClicked());
        if (id == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        open(event.getPlayer(), id, event.getRightClicked());
    }

    /** 左クリック（統合版のタップを含む）でも取り出せるようにする。お墓は壊れない。 */
    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        UUID id = graveId(event.getEntity());
        if (id == null) return;
        event.setCancelled(true);
        if (event.getDamager() instanceof Player player) open(player, id, event.getEntity());
    }

    /** チャンクの読み込み時に、記録のない目印を消し、目印が消えたお墓は作り直す。 */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        Set<UUID> present = new HashSet<>();
        for (Entity entity : event.getEntities()) {
            UUID id = graveId(entity);
            if (id == null) continue;
            GraveStore.Grave grave = store.get(id);
            if (grave == null || !grave.entities.contains(entity.getUniqueId())) entity.remove();
            else present.add(id);
        }
        String world = event.getWorld().getName();
        boolean changed = false;
        for (GraveStore.Grave grave : store.all()) {
            if (present.contains(grave.id) || !grave.world.equals(world)
                    || grave.chunkX() != event.getChunk().getX() || grave.chunkZ() != event.getChunk().getZ()) continue;
            spawn(grave);
            changed = true;
        }
        if (changed) store.save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean all = args.length == 1 && args[0].equalsIgnoreCase("all") && sender.hasPermission("ecolife.grave.admin");
        if (!all && !(sender instanceof Player)) {
            sender.sendMessage("ゲーム内から実行してください。コンソールでは /grave all で全員ぶんを表示します。");
            return true;
        }
        List<GraveStore.Grave> shown = new ArrayList<>();
        for (GraveStore.Grave grave : store.all()) {
            if (all || grave.owner.equals(((Player) sender).getUniqueId())) shown.add(grave);
        }
        if (shown.isEmpty()) {
            sender.sendMessage(prefixed(all ? "今あるお墓はありません。" : "あなたのお墓はありません。", NamedTextColor.GRAY));
            return true;
        }
        sender.sendMessage(prefixed(all ? "今あるお墓" : "あなたのお墓", NamedTextColor.GOLD));
        for (GraveStore.Grave grave : shown) {
            sender.sendMessage(Component.text((all ? grave.ownerName + " " : "") + describe(grave)
                    + " 残り" + minutesLeft(grave) + "分", NamedTextColor.WHITE));
        }
        return true;
    }

    private void open(Player player, UUID id, Entity clicked) {
        GraveStore.Grave grave = store.get(id);
        if (grave == null) {
            clicked.remove();
            return;
        }
        boolean owner = player.getUniqueId().equals(grave.owner);
        if (!owner && !player.hasPermission("ecolife.grave.admin")) {
            player.sendMessage(prefixed(grave.ownerName + " さんのお墓です。本人だけが取り出せます。残り"
                    + minutesLeft(grave) + "分で中身がその場に落ちます。", NamedTextColor.GRAY));
            return;
        }
        if (!store.remove(id)) return;
        removeEntities(grave);
        give(player, grave, owner);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        player.sendMessage(prefixed(owner ? "お墓から持ち物を取り出しました。"
                : grave.ownerName + " さんのお墓の中身を取り出しました。", NamedTextColor.GREEN));
        plugin.getLogger().info(player.getName() + " が " + grave.ownerName + " のお墓を取り出しました: " + describe(grave));
    }

    /** 本人なら空いている元のスロットへ戻し、入らないぶんは持ち物の空きへ、それでも入らなければ足元に落とす。 */
    private void give(Player player, GraveStore.Grave grave, boolean restoreSlots) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (GraveStore.Entry entry : grave.items) {
            int slot = entry.slot();
            if (restoreSlots && slot >= 0 && slot < inventory.getSize() && isEmpty(inventory.getItem(slot))) {
                inventory.setItem(slot, entry.item().clone());
            } else {
                rest.add(entry.item().clone());
            }
        }
        if (!rest.isEmpty()) {
            for (ItemStack overflow : inventory.addItem(rest.toArray(ItemStack[]::new)).values()) {
                player.getWorld().dropItem(player.getLocation(), overflow);
            }
        }
        if (grave.exp > 0) player.giveExp(grave.exp);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (GraveStore.Grave grave : List.copyOf(store.all())) {
            if (grave.expiresAt <= now) expire(grave);
            else for (UUID uuid : grave.entities) {
                if (Bukkit.getEntity(uuid) instanceof TextDisplay text) text.text(label(grave));
            }
        }
    }

    private void expire(GraveStore.Grave grave) {
        World world = Bukkit.getWorld(grave.world);
        if (world == null) return; // ワールドが読み込まれるまで中身を持ち越す。
        if (!store.remove(grave.id)) return;
        Location at = new Location(world, grave.x, grave.y + 0.5, grave.z);
        for (GraveStore.Entry entry : grave.items) world.dropItem(at, entry.item().clone());
        if (grave.exp > 0) world.spawn(at, ExperienceOrb.class, orb -> orb.setExperience(grave.exp));
        removeEntities(grave);
        Player owner = Bukkit.getPlayer(grave.owner);
        if (owner != null) {
            owner.sendMessage(prefixed("お墓の期限が切れ、中身がその場に落ちました: " + describe(grave)
                    + "。落ちたアイテムも時間がたつと消えます。", NamedTextColor.RED));
        }
        plugin.getLogger().info(grave.ownerName + " のお墓が期限切れになり、中身を落としました: " + describe(grave));
    }

    private void spawn(GraveStore.Grave grave) {
        World world = Bukkit.getWorld(grave.world);
        if (world == null) return;
        Location base = new Location(world, grave.x, grave.y, grave.z, grave.yaw, 0f);
        grave.entities.clear();
        ItemDisplay stone = world.spawn(base, ItemDisplay.class, display -> {
            display.setItemStack(displayItem());
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            // 平面のアイテムモデルは中心が原点に来るため、半ブロック持ち上げて地面に立たせる。
            display.setTransformation(new Transformation(new Vector3f(0f, 0.5f, 0f), new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
            tag(display, grave);
        });
        TextDisplay name = world.spawn(base.clone().add(0, 1.25, 0), TextDisplay.class, text -> {
            text.text(label(grave));
            text.setBillboard(Display.Billboard.CENTER);
            tag(text, grave);
        });
        Interaction hitbox = world.spawn(base, Interaction.class, interaction -> {
            interaction.setInteractionWidth(0.9f);
            interaction.setInteractionHeight(1.1f);
            interaction.setResponsive(true);
            tag(interaction, grave);
        });
        grave.entities.add(stone.getUniqueId());
        grave.entities.add(name.getUniqueId());
        grave.entities.add(hitbox.getUniqueId());
    }

    private void removeEntities(GraveStore.Grave grave) {
        for (UUID uuid : grave.entities) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) entity.remove();
        }
        // 未読み込みのチャンクに残った目印は、次に読み込まれたとき onEntitiesLoad が消す。
    }

    private void tag(Entity entity, GraveStore.Grave grave) {
        entity.setPersistent(true);
        entity.getPersistentDataContainer().set(marker, PersistentDataType.STRING, grave.id.toString());
    }

    private UUID graveId(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 専用テクスチャを読み込めない場合も、お墓らしく見えるよう丸石の塀を元にする。 */
    private ItemStack displayItem() {
        ItemStack stack = new ItemStack(Material.COBBLESTONE_WALL);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(model);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * 足元から下へたどり、最初の固体か液体の上に置く。奈落に落ちた場合は、その真上で一番高いブロックの上、
     * それもなければワールドのスポーン地点に置く。
     */
    public static Location placeFor(Location death) {
        World world = death.getWorld();
        int min = world.getMinHeight();
        int max = world.getMaxHeight();
        int x = death.getBlockX();
        int z = death.getBlockZ();
        int start = Math.min(Math.max(death.getBlockY(), min), max - 2);
        for (int y = start; y > min; y--) {
            Block below = world.getBlockAt(x, y - 1, z);
            if (below.isSolid() || below.isLiquid()) return new Location(world, x + 0.5, y, z + 0.5);
        }
        Block top = world.getHighestBlockAt(x, z);
        if (!top.getType().isAir() && top.getY() >= min) {
            return new Location(world, x + 0.5, Math.min(top.getY() + 1, max - 2), z + 0.5);
        }
        Location spawn = world.getSpawnLocation();
        return new Location(world, spawn.getBlockX() + 0.5, spawn.getBlockY(), spawn.getBlockZ() + 0.5);
    }

    /** 墓石の向きを東西南北にそろえる。 */
    private static float snapYaw(float yaw) {
        return Math.round(yaw / 90f) * 90f;
    }

    private Component label(GraveStore.Grave grave) {
        return Component.text(grave.ownerName + " のお墓", NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text("残り" + minutesLeft(grave) + "分", NamedTextColor.GRAY));
    }

    private static long minutesLeft(GraveStore.Grave grave) {
        long millis = Math.max(0, grave.expiresAt - System.currentTimeMillis());
        return (millis + 59_999) / 60_000;
    }

    private static String describe(GraveStore.Grave grave) {
        return grave.world + " (" + (int) Math.floor(grave.x) + ", " + (int) Math.floor(grave.y) + ", "
                + (int) Math.floor(grave.z) + ")";
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    private static Component prefixed(String message, NamedTextColor color) {
        return Component.text("[お墓] ", NamedTextColor.DARK_GRAY).append(Component.text(message, color));
    }
}
