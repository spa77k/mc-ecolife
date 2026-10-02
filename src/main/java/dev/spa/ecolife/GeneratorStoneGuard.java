package dev.spa.ecolife;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 溶岩と水で生まれた石（石製造機）を掘っても、Jobs の職業報酬を出さない。
 * 生まれた場所はチャンクに記録するので、再起動をまたいでも効く。ピストンで動いた石も追いかける。
 * Jobs へのコンパイル時依存は持たず、支払い前イベントを名前で購読する。
 */
final class GeneratorStoneGuard implements Listener {

    private static final String PRE_PAYMENT_EVENT = "com.gamingmesh.jobs.api.JobsPrePaymentEvent";
    /** 支払いを止める行動。手で掘る・TNTで壊す。 */
    private static final Set<String> GUARDED_ACTIONS = Set.of("BREAK", "TNTBREAK");

    private final JavaPlugin plugin;
    private final NamespacedKey key;
    private boolean enabled;
    private Set<Material> materials = EnumSet.noneOf(Material.class);

    GeneratorStoneGuard(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "generated_stone");
    }

    /** Jobs があれば、記録用のリスナーと支払い前イベントの購読を登録する。 */
    void register() {
        Plugin jobs = plugin.getServer().getPluginManager().getPlugin("Jobs");
        if (jobs == null || !jobs.isEnabled()) {
            plugin.getLogger().info("Jobs が見つからないため、石製造機の石の報酬停止は使いません。");
            return;
        }
        Class<? extends Event> eventClass;
        Method getBlock;
        Method getActionInfo;
        try {
            eventClass = Class.forName(PRE_PAYMENT_EVENT, true, jobs.getClass().getClassLoader())
                    .asSubclass(Event.class);
            getBlock = eventClass.getMethod("getBlock");
            getActionInfo = eventClass.getMethod("getActionInfo");
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            plugin.getLogger().warning("Jobs の支払い前イベントを購読できないため、石製造機の石の報酬停止は使いません: " + e);
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getPluginManager().registerEvent(eventClass, this, EventPriority.HIGHEST,
                (listener, event) -> onPrePayment(event, getBlock, getActionInfo), plugin, true);
        reload();
    }

    void reload() {
        enabled = plugin.getConfig().getBoolean("generator-stone.enabled", true);
        Set<Material> loaded = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("generator-stone.materials")) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) {
                plugin.getLogger().warning("generator-stone.materials の " + name + " はブロックではないため無視します。");
                continue;
            }
            loaded.add(material);
        }
        materials = loaded;
        if (enabled) {
            plugin.getLogger().info("石製造機で生まれた " + materials + " は職業報酬の対象外にします。");
        }
    }

    private void onPrePayment(Event event, Method getBlock, Method getActionInfo) {
        if (!enabled || !(event instanceof Cancellable cancellable)) {
            return;
        }
        try {
            if (!(getBlock.invoke(event) instanceof Block block) || !isMarked(block)) {
                return;
            }
            Object info = getActionInfo.invoke(event);
            Object type = info == null ? null : info.getClass().getMethod("getType").invoke(info);
            if (type != null && GUARDED_ACTIONS.contains(type.toString())) {
                cancellable.setCancelled(true);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("石製造機の石の判定に失敗しました: " + e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (enabled && materials.contains(event.getNewState().getType())) {
            mark(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        forgetLater(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        forgetLater(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        forgetLater(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    /**
     * 壊れた場所の記録を次のティックで消す。Jobs の支払いは壊れる前に判定されるので、
     * 同じイベント中に消すと先に消えてしまうことがある。その間に石が生まれ直していれば残す。
     */
    private void forgetLater(Collection<Block> blocks) {
        List<Block> marked = new ArrayList<>();
        for (Block block : blocks) {
            if (isMarked(block)) {
                marked.add(block);
            }
        }
        if (marked.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Block block : marked) {
                if (!materials.contains(block.getType())) {
                    unmark(block);
                }
            }
        });
    }

    /** ピストンで動く石の記録を、動いた先へ付け替える。連なって動くので、先に全部外してから付け直す。 */
    private void move(List<Block> blocks, BlockFace direction) {
        List<Block> marked = new ArrayList<>();
        for (Block block : blocks) {
            if (isMarked(block)) {
                marked.add(block);
            }
        }
        for (Block block : marked) {
            unmark(block);
        }
        for (Block block : marked) {
            mark(block.getRelative(direction));
        }
    }

    // --- チャンクへの記録。チャンク内の位置を1つの整数に詰めて並べる ---

    private static int pack(Block block) {
        return ((block.getY() + 2048) << 8) | ((block.getZ() & 15) << 4) | (block.getX() & 15);
    }

    private int[] marks(Chunk chunk) {
        int[] marks = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        return marks == null ? new int[0] : marks;
    }

    private boolean isMarked(Block block) {
        int packed = pack(block);
        for (int mark : marks(block.getChunk())) {
            if (mark == packed) {
                return true;
            }
        }
        return false;
    }

    private void mark(Block block) {
        Chunk chunk = block.getChunk();
        int[] marks = marks(chunk);
        int packed = pack(block);
        for (int mark : marks) {
            if (mark == packed) {
                return;
            }
        }
        int[] grown = Arrays.copyOf(marks, marks.length + 1);
        grown[marks.length] = packed;
        chunk.getPersistentDataContainer().set(key, PersistentDataType.INTEGER_ARRAY, grown);
    }

    private void unmark(Block block) {
        Chunk chunk = block.getChunk();
        int packed = pack(block);
        int[] remaining = Arrays.stream(marks(chunk)).filter(mark -> mark != packed).toArray();
        PersistentDataContainer container = chunk.getPersistentDataContainer();
        if (remaining.length == 0) {
            container.remove(key);
        } else {
            container.set(key, PersistentDataType.INTEGER_ARRAY, remaining);
        }
    }
}
