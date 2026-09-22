package dev.spa.ecolife.poster;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;

/** 運営管理の画像カタログ。取得済みの画像と地図IDはカタログ削除後も保持する。 */
public final class PosterService implements Listener, TabExecutor {
    private record Poster(String id, String title, int columns, int rows, PosterImage.Prepared image) {}
    private record Catalog(List<Poster> posters, List<String> errors) {}
    private final JavaPlugin plugin;
    private final Path directory;
    private final YamlConfiguration registry = new YamlConfiguration();
    private final Map<Integer, BufferedImage> rendered = new HashMap<>();
    private List<Poster> posters = List.of();
    private boolean loading;

    public PosterService(JavaPlugin plugin) throws Exception {
        this.plugin = plugin;
        directory = plugin.getDataFolder().toPath().resolve("posters");
        Files.createDirectories(directory.resolve("images"));
        Files.createDirectories(directory.resolve("cache"));
        if (!Files.exists(directory.resolve("catalog.yml"))) plugin.saveResource("posters/catalog.yml", false);
        Path state = directory.resolve("maps.yml");
        if (Files.exists(state)) registry.load(state.toFile()); // 壊れたID記録を空として上書きしない。
        Set<Integer> seenIds = new HashSet<>();
        for (String key : registry.getKeys(false)) {
            if (!key.matches("[0-9a-f]{64}")) throw new IOException("地図記録のキーが不正です");
            List<Integer> ids = registry.getIntegerList(key);
            List<?> rawIds = registry.getList(key);
            if (rawIds == null || ids.isEmpty() || ids.size() > 16 || rawIds.size() != ids.size()
                    || rawIds.stream().anyMatch(value -> !(value instanceof Integer))
                    || ids.stream().anyMatch(id -> id < 0 || !seenIds.add(id)))
                throw new IOException("地図ID記録が不正です: " + key);
            for (int i = 0; i < ids.size(); i++) {
                BufferedImage tile = ImageIO.read(directory.resolve("cache/" + key + "/" + i + ".png").toFile());
                if (tile == null || tile.getWidth() != 128 || tile.getHeight() != 128)
                    throw new IOException("保存済みのポスター画像が不正です: " + key);
                rendered.put(ids.get(i), tile);
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (int id : rendered.keySet()) {
            MapView map = Bukkit.getMap(id);
            if (map != null) attach(map);
        }
        Objects.requireNonNull(plugin.getCommand("poster")).setExecutor(this);
        Objects.requireNonNull(plugin.getCommand("poster")).setTabCompleter(this);
        reload(Bukkit.getConsoleSender());
    }

    private Catalog readCatalog() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(directory.resolve("catalog.yml").toFile());
        var section = config.getConfigurationSection("posters");
        if (section == null) throw new IOException("postersセクションがありません");
        if (section.getKeys(false).size() > 128) throw new IOException("登録は128種類までです");
        List<Poster> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            try {
                if (!id.matches("[a-z0-9_-]{1,48}")) throw new IOException("IDは英小文字・数字・_・-の48文字以内です");
                var item = section.getConfigurationSection(id);
                if (item == null) throw new IOException("設定の形式が不正です");
                if (!item.getBoolean("enabled", true)) continue;
                int columns = item.getInt("width", 1), rows = item.getInt("height", 1);
                String title = item.getString("title", id);
                if (title.isBlank() || title.length() > 80) throw new IOException("表示名は1〜80文字です");
                result.add(new Poster(id, title, columns, rows,
                        PosterImage.read(directory.resolve("images"), item.getString("file", ""), columns, rows)));
            } catch (Exception e) { errors.add(id + ": " + e.getMessage()); }
        }
        return new Catalog(List.copyOf(result), List.copyOf(errors));
    }

    private void reload(CommandSender sender) {
        if (loading) { sender.sendMessage("ポスターを読み込み中です。"); return; }
        loading = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Catalog catalog = readCatalog();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loading = false;
                    // 一部だけ配布不可にしない。設定ミス時は直前の一覧を維持する。
                    if (!catalog.errors().isEmpty()) {
                        sender.sendMessage("読み込みを中止しました。現在の一覧を維持します。詳細はサーバーログを確認してください。");
                        catalog.errors().forEach(error -> plugin.getLogger().warning("ポスター: " + error));
                        return;
                    }
                    posters = catalog.posters();
                    closeMenus();
                    sender.sendMessage("ポスターを " + posters.size() + " 種類読み込みました。");
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "ポスター一覧の読み込みに失敗しました", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loading = false;
                    sender.sendMessage("読み込みに失敗しました。現在の一覧を維持します。サーバーログを確認してください。");
                });
            }
        });
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("ecolife.poster.admin")) sender.sendMessage("この操作は運営専用です。");
            else reload(sender);
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("ゲーム内で /poster を使ってください。運営: /poster reload"); return true; }
        if (!player.hasPermission("ecolife.poster")) { player.sendMessage("ポスターを利用する権限がありません。"); return true; }
        if (args.length != 0) { player.sendMessage("/poster でポスター一覧を開きます。"); return true; }
        if (posters.isEmpty()) { player.sendMessage(loading ? "ポスターを読み込み中です。少し待ってください。" : "ポスターはまだ登録されていません。"); return true; }
        open(player, 0);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 && sender.hasPermission("ecolife.poster.admin") && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))
                ? List.of("reload") : List.of();
    }

    private static final class Menu implements InventoryHolder {
        final int page;
        final List<Poster> entries;
        Inventory inventory;
        Menu(int page, List<Poster> entries) { this.page = page; this.entries = entries; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private void open(Player player, int page) {
        Menu menu = new Menu(page, posters);
        menu.inventory = Bukkit.createInventory(menu, 54, Component.text("ポスター一覧 " + (page + 1) + "/" + ((posters.size() + 44) / 45)));
        for (int slot = 0; slot < 45 && page * 45 + slot < posters.size(); slot++) {
            Poster poster = posters.get(page * 45 + slot);
            menu.inventory.setItem(slot, item(Material.PAINTING, poster.title(),
                    poster.columns() + " × " + poster.rows() + " ブロック", "クリックで地図一式を受け取る", "額縁は自分で用意してください"));
        }
        if (page > 0) menu.inventory.setItem(45, item(Material.ARROW, "前のページ"));
        menu.inventory.setItem(49, item(Material.BOOK, "飾り方", "地図を額縁に入れて飾れます", "大型は各地図の行・列の順に並べます", "追加要望は既存のフィードバックへ"));
        if ((page + 1) * 45 < posters.size()) menu.inventory.setItem(53, item(Material.ARROW, "次のページ"));
        player.openInventory(menu.inventory);
    }

    private static ItemStack item(Material material, String title, String... lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(Arrays.stream(lore).map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true); // 下側のシフト移動、数字キー、ダブルクリックも遮断する。
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("ecolife.poster")) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54 || !event.isLeftClick() || event.isShiftClick()) return;
        if (menu.entries != posters) { player.closeInventory(); return; }
        if (slot == 45 && menu.page > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (menu.entries == posters) open(player, menu.page - 1); });
        } else if (slot == 53 && (menu.page + 1) * 45 < posters.size()) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (menu.entries == posters) open(player, menu.page + 1); });
        } else if (slot < 45 && menu.page * 45 + slot < posters.size()) {
            give(player, posters.get(menu.page * 45 + slot));
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    private void give(Player player, Poster poster) {
        int required = poster.columns() * poster.rows();
        long free = Arrays.stream(player.getInventory().getStorageContents()).filter(i -> i == null || i.getType().isAir()).count();
        if (free < required) { player.sendMessage("持ち物に " + required + " 枠の空きを作ってください。"); return; }
        try {
            List<Integer> ids = maps(poster, player);
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                ItemStack item = item(Material.FILLED_MAP, poster.title() + "（" + (i / poster.columns() + 1) + "行 " + (i % poster.columns() + 1) + "列）",
                        "左上から行・列の順に額縁へ入れてください");
                MapMeta meta = (MapMeta) item.getItemMeta();
                meta.setMapView(Objects.requireNonNull(Bukkit.getMap(ids.get(i)), "保存済みの地図が見つかりません"));
                item.setItemMeta(meta);
                items.add(item);
            }
            player.getInventory().addItem(items.toArray(ItemStack[]::new));
            player.sendMessage(poster.title() + "を受け取りました。額縁に入れて飾ってください。");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "ポスターの配布に失敗しました: " + poster.id(), e);
            player.sendMessage("ポスターを用意できませんでした。運営へお知らせください。");
        }
    }

    private List<Integer> maps(Poster poster, Player player) throws IOException {
        String key = poster.image().key();
        if (registry.contains(key)) return registry.getIntegerList(key);
        Path cache = directory.resolve("cache/" + key);
        Files.createDirectories(cache);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < poster.image().tiles().size(); i++) {
            BufferedImage tile = poster.image().tiles().get(i);
            ImageIO.write(tile, "png", cache.resolve(i + ".png").toFile());
            MapView map = Bukkit.createMap(player.getWorld());
            ids.add(map.getId());
        }
        registry.set(key, ids);
        Path temporary = directory.resolve("maps.yml.tmp");
        try {
            registry.save(temporary.toFile());
            Files.move(temporary, directory.resolve("maps.yml"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { registry.set(key, null); throw e; }
        for (int i = 0; i < ids.size(); i++) {
            rendered.put(ids.get(i), poster.image().tiles().get(i));
            attach(Objects.requireNonNull(Bukkit.getMap(ids.get(i))));
        }
        return ids;
    }

    @EventHandler public void initialize(MapInitializeEvent event) { attach(event.getMap()); }

    private void attach(MapView map) {
        BufferedImage image = rendered.get(map.getId());
        if (image == null) return;
        for (MapRenderer renderer : map.getRenderers()) map.removeRenderer(renderer);
        map.setTrackingPosition(false);
        map.setUnlimitedTracking(false);
        map.setLocked(true);
        map.addRenderer(new MapRenderer(false) {
            private boolean drawn;
            @Override public void render(MapView view, MapCanvas canvas, Player player) {
                if (!drawn) {
                    canvas.drawImage(0, 0, image);
                    canvas.setCursors(new MapCursorCollection());
                    drawn = true;
                }
            }
        });
    }

    private void closeMenus() {
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) player.closeInventory();
    }

    public void close() { closeMenus(); }
}
