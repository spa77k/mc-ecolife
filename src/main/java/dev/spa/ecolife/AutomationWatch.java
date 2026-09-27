package dev.spa.ecolife;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 全自動装置の疑いがある場所を見つけて運営用Discordへ知らせる。通知だけで、装置には手を出さない。
 *
 * <p>ルールの基準は「プレイヤーがその場で操作しないと止まるか」。そこで、近くのプレイヤーが全員放置中
 * （または誰もいない）のに、収穫・処理・回収に当たる動きが続いているチャンクを数える。
 * プレイヤーが自分で倒したモブや、ボタンを押している間だけ動く半自動装置は、操作した本人が
 * アクティブ扱いになるため対象にならない。一度通知したチャンクはDBに残し、以後は通知しない。
 */
final class AutomationWatch implements Listener {

    /** 数える動き。key は config.yml の weights のキー。 */
    enum Kind {
        TRANSFER("transfer", 1, "ホッパー等の搬送"),
        PICKUP("pickup", 1, "ホッパーのアイテム回収"),
        PISTON("piston", 1, "ピストン"),
        DISPENSE("dispense", 1, "ディスペンサー・ドロッパー"),
        MOB_DEATH("mob-death", 10, "プレイヤー以外によるモブの死亡");

        final String key;
        final int defaultWeight;
        final String label;

        Kind(String key, int defaultWeight, String label) {
            this.key = key;
            this.defaultWeight = defaultWeight;
            this.label = label;
        }
    }

    private record ChunkKey(UUID world, int x, int z) {
    }

    /** 1分ごとの集計。counts は Kind ごとの回数。 */
    private record Minute(int[] counts, int score, boolean unattended) {
    }

    private static final class ChunkState {
        int[] current = new int[Kind.values().length];
        final ArrayDeque<Minute> minutes = new ArrayDeque<>();
        final Map<Long, Integer> spots = new HashMap<>();
    }

    private final JavaPlugin plugin;
    private final Map<ChunkKey, ChunkState> states = new HashMap<>();
    /** 通知済み・除外対象で、以後数えないチャンク。 */
    private final Set<ChunkKey> ignored = new HashSet<>();
    private final Map<UUID, Long> lastActive = new HashMap<>();
    private final Set<Long> inFlight = new HashSet<>();

    private AutomationStore store;
    private Set<String> notified = new HashSet<>();
    private volatile AutomationConfig config;
    private DiscordWebhook webhook;
    private ExecutorService sender;
    private BukkitTask task;
    private boolean active;
    private long lastResendMillis;

    AutomationWatch(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 設定を読み込んで見回りを始める。無効なら何も数えない。 */
    void start(AutomationConfig config) {
        this.config = config;
        states.clear();
        ignored.clear();
        if (!config.enabled()) {
            return;
        }
        try {
            if (store == null) {
                store = new AutomationStore(plugin.getDataFolder().toPath().resolve("automation.db"));
            }
            notified = store.keys();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "自動化装置の検出記録を開けないため、検出を停止します。", e);
            return;
        }
        webhook = new DiscordWebhook(Duration.ofSeconds(5), Duration.ofSeconds(10), plugin.getLogger());
        sender = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "EcoLifeAssist-AutomationNotify");
            thread.setDaemon(true);
            return thread;
        });
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            lastActive.putIfAbsent(player.getUniqueId(), now);
        }
        long period = config.checkIntervalSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
        active = true;
    }

    void stop() {
        active = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (sender != null) {
            sender.shutdown();
            try {
                sender.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sender = null;
        }
        inFlight.clear();
    }

    void close() {
        stop();
        if (store != null) {
            try {
                store.close();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "自動化装置の検出記録を閉じられませんでした。", e);
            }
            store = null;
        }
    }

    /** /ecolife automation status 用。 */
    List<String> statusLines() {
        AutomationConfig current = config;
        List<String> lines = new ArrayList<>();
        lines.add("&7設定: &f" + (current != null && current.enabled() ? "有効" : "無効")
                + " &7/ URL: &f" + (current != null && current.webhookConfigured() ? "設定済み" : "未設定")
                + " &7/ 稼働: &f" + (active ? "はい" : "いいえ"));
        if (store != null) {
            try {
                lines.add("&7監視中のチャンク: &f" + states.size()
                        + " &7/ 通知済みの場所: &f" + store.count(false)
                        + " &7/ 未送信: &f" + store.count(true));
            } catch (SQLException e) {
                lines.add("&c検出記録を読めませんでした。");
            }
        }
        return lines;
    }

    /** 運営用Webhookへテスト送信する。送れる状態なら true。 */
    boolean sendTest() {
        AutomationConfig current = config;
        if (!active || !current.webhookConfigured()) {
            return false;
        }
        sender.submit(() -> webhook.send(current.webhookUrl(), current.username(), current.avatarUrl(),
                "🔔 自動化装置の検出通知のテスト送信です。"));
        return true;
    }

    // --- 数える動き ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransfer(InventoryMoveItemEvent event) {
        count(event.getInitiator().getLocation(), Kind.TRANSFER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(InventoryPickupItemEvent event) {
        count(event.getInventory().getLocation(), Kind.PICKUP);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        count(event.getBlock(), Kind.PISTON);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        count(event.getBlock(), Kind.DISPENSE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        // プレイヤーが倒したモブは「本人が仕上げている」ので数えない
        if (entity instanceof Player || entity instanceof ArmorStand || entity.getKiller() != null) {
            return;
        }
        count(entity.getLocation(), Kind.MOB_DEATH);
    }

    // --- 放置判定（McLevel の ActivityTracker と同じ操作を自発的な操作とみなす） ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastActive.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            markActive(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markActive(player);
        }
    }

    private void markActive(Player player) {
        if (active) {
            lastActive.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    private void count(Block block, Kind kind) {
        count(block.getWorld(), block.getX(), block.getY(), block.getZ(), kind);
    }

    private void count(Location location, Kind kind) {
        if (location != null && location.getWorld() != null) {
            count(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), kind);
        }
    }

    private void count(World world, int x, int y, int z, Kind kind) {
        if (!active) {
            return;
        }
        ChunkKey key = new ChunkKey(world.getUID(), x >> 4, z >> 4);
        if (ignored.contains(key)) {
            return;
        }
        ChunkState state = states.computeIfAbsent(key, k -> new ChunkState());
        state.current[kind.ordinal()]++;
        state.spots.merge(pack(x, y, z), 1, Integer::sum);
    }

    // --- 見回り ---

    /** 1区切りごとに、動きのあったチャンクへ「放置中だったか」を記録し、条件を満たしたら通知する。 */
    private void tick() {
        AutomationConfig current = config;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<ChunkKey, ChunkState>> it = states.entrySet().iterator();
        List<Map.Entry<ChunkKey, ChunkState>> detected = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<ChunkKey, ChunkState> entry = it.next();
            ChunkKey key = entry.getKey();
            ChunkState state = entry.getValue();
            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                it.remove();
                continue;
            }
            if (current.excludedWorlds().contains(world.getName())
                    || notified.contains(AutomationStore.key(world.getName(), key.x(), key.z()))) {
                ignored.add(key);
                it.remove();
                continue;
            }
            int score = 0;
            for (Kind kind : Kind.values()) {
                score += state.current[kind.ordinal()] * current.weights()[kind.ordinal()];
            }
            state.minutes.addLast(new Minute(state.current, score, isUnattended(world, key, now, current)));
            state.current = new int[Kind.values().length];
            while (state.minutes.size() > current.windowMinutes()) {
                state.minutes.removeFirst();
            }
            if (state.minutes.stream().allMatch(m -> m.score() == 0)) {
                it.remove();
                continue;
            }
            if (qualifies(state, current)) {
                detected.add(entry);
                it.remove();
            }
        }
        for (Map.Entry<ChunkKey, ChunkState> entry : detected) {
            report(entry.getKey(), entry.getValue(), current);
        }
        resendUnsent(current);
    }

    private boolean qualifies(ChunkState state, AutomationConfig config) {
        if (state.minutes.size() < config.windowMinutes()) {
            return false;
        }
        int activeMinutes = 0;
        int total = 0;
        for (Minute minute : state.minutes) {
            if (!minute.unattended()) {
                return false;
            }
            if (minute.score() > 0) {
                activeMinutes++;
            }
            total += minute.score();
        }
        return activeMinutes >= config.minActiveMinutes() && total >= config.minScore();
    }

    /** 半径内にアクティブなプレイヤーが1人もいなければ放置中とみなす。誰もいない場合も含む。 */
    private boolean isUnattended(World world, ChunkKey key, long now, AutomationConfig config) {
        double centerX = (key.x() << 4) + 8;
        double centerZ = (key.z() << 4) + 8;
        double radiusSquared = (double) config.radiusBlocks() * config.radiusBlocks();
        long afkMillis = config.afkSeconds() * 1000L;
        for (Player player : world.getPlayers()) {
            Location loc = player.getLocation();
            double dx = loc.getX() - centerX;
            double dz = loc.getZ() - centerZ;
            if (dx * dx + dz * dz > radiusSquared) {
                continue;
            }
            Long last = lastActive.get(player.getUniqueId());
            if (last != null && now - last <= afkMillis) {
                return false;
            }
        }
        return true;
    }

    private void report(ChunkKey key, ChunkState state, AutomationConfig config) {
        World world = Bukkit.getWorld(key.world());
        if (world == null) {
            return;
        }
        long spot = state.spots.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(pack((key.x() << 4) + 8, world.getSeaLevel(), (key.z() << 4) + 8));
        int x = unpackX(spot);
        int y = unpackY(spot);
        int z = unpackZ(spot);
        Location location = new Location(world, x, y, z);

        int[] totals = new int[Kind.values().length];
        for (Minute minute : state.minutes) {
            for (int i = 0; i < totals.length; i++) {
                totals[i] += minute.counts()[i];
            }
        }
        StringBuilder breakdown = new StringBuilder();
        for (Kind kind : Kind.values()) {
            if (totals[kind.ordinal()] > 0) {
                if (!breakdown.isEmpty()) {
                    breakdown.append(" / ");
                }
                breakdown.append(kind.label).append(' ').append(totals[kind.ordinal()]).append("回");
            }
        }

        List<String> nearby = new ArrayList<>();
        double radiusSquared = (double) config.radiusBlocks() * config.radiusBlocks();
        for (Player player : world.getPlayers()) {
            Location loc = player.getLocation();
            double dx = loc.getX() - x;
            double dz = loc.getZ() - z;
            if (dx * dx + dz * dz <= radiusSquared) {
                nearby.add(NotifyText.sanitize(player.getName(), 32));
            }
        }

        String worldName = NotifyText.sanitize(world.getName(), 40);
        String content = "🚨 **自動化装置の疑い**\n"
                + "場所: " + worldName + " / X " + x + " Y " + y + " Z " + z
                + "（チャンク " + key.x() + ", " + key.z() + "）\n"
                + "土地の持ち主: " + NotifyText.sanitize(claimOwner(location), 40) + "\n"
                + "近くのプレイヤー: " + (nearby.isEmpty() ? "なし" : String.join("、", nearby) + "（全員放置中）") + "\n"
                + "直近" + config.windowMinutes() + "分の動き: " + breakdown + "\n"
                + "現地や /co inspect で確認してください。この場所は今後通知しません。";

        long id;
        try {
            id = store.insert(world.getName(), key.x(), key.z(), x, y, z, content);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "自動化装置の検出を記録できませんでした。", e);
            return;
        }
        notified.add(AutomationStore.key(world.getName(), key.x(), key.z()));
        ignored.add(key);
        if (id < 0) {
            return;
        }
        plugin.getLogger().warning("自動化装置の疑い: " + world.getName() + " " + x + " " + y + " " + z
                + "（" + breakdown + "）");
        send(id, content, config);
    }

    private void resendUnsent(AutomationConfig config) {
        // 送信に失敗し続けるURLでもログが毎分流れないよう、再送は10分に1回までにする
        long now = System.currentTimeMillis();
        if (!config.webhookConfigured() || now - lastResendMillis < 600_000L) {
            return;
        }
        lastResendMillis = now;
        try {
            for (AutomationStore.Pending pending : store.unsent(3)) {
                send(pending.id(), pending.content(), config);
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "未送信の検出記録を読めませんでした。", e);
        }
    }

    private void send(long id, String content, AutomationConfig config) {
        if (!config.webhookConfigured() || sender == null || !inFlight.add(id)) {
            return;
        }
        sender.submit(() -> {
            boolean ok = webhook.send(config.webhookUrl(), config.username(), config.avatarUrl(), content);
            Bukkit.getScheduler().runTask(plugin, () -> {
                inFlight.remove(id);
                if (ok && store != null) {
                    try {
                        store.markSent(id);
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.WARNING, "検出記録の送信済みを保存できませんでした。", e);
                    }
                }
            });
        });
    }

    /** GriefPrevention の土地の持ち主。コンパイル時依存にしないため、公開APIをリフレクションで呼ぶ。 */
    private static String claimOwner(Location location) {
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (gp == null || !gp.isEnabled()) {
            return "不明（GriefPrevention未導入）";
        }
        try {
            Object dataStore = gp.getClass().getField("dataStore").get(gp);
            Class<?> claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim", false,
                    gp.getClass().getClassLoader());
            Method getClaimAt = dataStore.getClass().getMethod("getClaimAt", Location.class, boolean.class, claimClass);
            Object claim = getClaimAt.invoke(dataStore, location, true, null);
            if (claim == null) {
                return "なし（保護されていない場所）";
            }
            Object owner = claim.getClass().getMethod("getOwnerName").invoke(claim);
            return owner == null ? "不明" : owner.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "不明";
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    private static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }
}
