package dev.spa.ecolife;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** よく使う機能への入口。実際の操作は各プラグインのプレイヤーコマンドに委ねる。 */
final class PhoneService implements Listener, CommandExecutor {
    private enum Page { HOME, TRADE, TRAVEL, PLAY, HELP, SET_HOME }

    private static final class PhoneMenu implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        @Override public Inventory getInventory() { return inventory; }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey marker;
    private final NamespacedKey model;

    PhoneService(JavaPlugin plugin) {
        this.plugin = plugin;
        marker = new NamespacedKey(plugin, "phone");
        model = new NamespacedKey("ecolife", "smartphone");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (player.isOnline()) giveIfMissing(player, false); }, 40L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (player.isOnline()) giveIfMissing(player, false); }, 2L);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || !isPhone(event.getItem())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("ecolife.phone")) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) open(player, Page.HOME); });
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isPhone(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isPhone);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PhoneMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        Consumer<Player> action = menu.actions.get(slot);
        if (action == null) return;
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline()) action.accept(player); });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PhoneMenu) event.setCancelled(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("ゲーム内から実行してください。");
            return true;
        }
        if (!player.hasPermission("ecolife.phone")) {
            player.sendMessage("この機能を使う権限がありません。");
            return true;
        }
        if (args.length == 0) open(player, Page.HOME);
        else if (args.length == 1 && args[0].equalsIgnoreCase("get")) giveIfMissing(player, true);
        else player.sendMessage("使い方: /phone [get]");
        return true;
    }

    private boolean isPhone(ItemStack stack) {
        if (stack == null || stack.getType() != Material.CLOCK || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    private boolean hasPhone(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) if (isPhone(stack)) return true;
        for (ItemStack stack : player.getEnderChest().getContents()) if (isPhone(stack)) return true;
        return false;
    }

    private void giveIfMissing(Player player, boolean requested) {
        if (!player.hasPermission("ecolife.phone")) return;
        if (hasPhone(player)) {
            if (requested) player.sendMessage("スマホはすでに持っています。");
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            if (requested) player.sendMessage("持ち物に空きを作ってから /phone get を実行してください。/phone なら今すぐ開けます。");
            return;
        }
        ItemStack stack = new ItemStack(Material.CLOCK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("スマホ", NamedTextColor.AQUA));
        meta.lore(List.of(Component.text("右クリックで便利なメニューを開く", NamedTextColor.GRAY),
                Component.text("紛失したら /phone get", NamedTextColor.DARK_GRAY)));
        meta.setItemModel(model);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        player.getInventory().addItem(stack);
        player.sendMessage("スマホを受け取りました。右クリックでメニューを開けます。");
    }

    private void open(Player player, Page page) {
        PhoneMenu holder = new PhoneMenu();
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("スマホ - " + title(page)));
        switch (page) {
            case HOME -> {
                command(holder, 13, Material.GOLD_INGOT, "オークション", "出品・入札・受け取り", "ah");
                page(holder, 10, Material.EMERALD, "売り買い", "依頼所・ショップ・所持金も見る", Page.TRADE);
                page(holder, 12, Material.COMPASS, "移動", "ロビー・ランダム移動・ホーム", Page.TRAVEL);
                page(holder, 14, Material.EXPERIENCE_BOTTLE, "遊びと記録", "職業・レベル・招待・ポスター", Page.PLAY);
                page(holder, 16, Material.BOOK, "案内と相談", "遊び方・ルール・フィードバック", Page.HELP);
                item(holder, 22, Material.CLOCK, "スマホ", "各アイコンから機能を選べます", null);
            }
            case TRADE -> {
                command(holder, 10, Material.GOLD_INGOT, "オークション", "出品を見て、入札・購入する", "ah");
                command(holder, 11, Material.WRITABLE_BOOK, "オークションに出品", "手に売りたい物を持って選ぶ", "ah sell");
                command(holder, 12, Material.CHEST, "オークション保管庫", "落札品・返却品を受け取る", "ah vault");
                command(holder, 13, Material.PAPER, "依頼所", "依頼を探す・作る・受ける", "irai");
                command(holder, 14, Material.EMERALD, "プレイヤーショップ", "ショップ一覧を開く", "qs browse");
                command(holder, 15, Material.GOLD_NUGGET, "所持金", "残高を確認する", "balance");
                help(holder, 16, Material.NAME_TAG, "送金", "相手と金額を入力", "/pay <名前> <金額>");
                command(holder, 18, Material.NAME_TAG, "自分の出品", "出品中のアイテムを確認", "ah my");
                command(holder, 19, Material.IRON_INGOT, "入札中", "自分の入札を確認", "ah bids");
                command(holder, 20, Material.WRITTEN_BOOK, "自分の依頼", "依頼の進み具合を確認", "irai my");
            }
            case TRAVEL -> {
                command(holder, 10, Material.COMPASS, "ロビーへ戻る", "戦闘中は移動できません", "lobby");
                command(holder, 11, Material.ENDER_PEARL, "ランダム移動", "安全な場所へ移動する", "rtp");
                command(holder, 12, Material.RED_BED, "ホームへ帰る", "登録済みのホームへ移動", "home");
                page(holder, 13, Material.WHITE_BED, "ホームを登録", "現在地をホームにする（100S）", Page.SET_HOME);
                command(holder, 14, Material.OAK_DOOR, "案内所", "資源・建築ワールドへ行く", "menu");
                help(holder, 15, Material.MAP, "2か所目のホーム", "番号を指定して使う", "/sethome 2、/home 2");
            }
            case PLAY -> {
                command(holder, 9, Material.IRON_PICKAXE, "職業を選ぶ", "仕事の一覧から就職する", "jobs browse");
                command(holder, 10, Material.IRON_SWORD, "職業の記録", "仕事の進み具合を見る", "jobs stats");
                command(holder, 11, Material.EXPERIENCE_BOTTLE, "レベル", "次の解放までの進み具合", "level");
                command(holder, 12, Material.KNOWLEDGE_BOOK, "進捗", "進捗の一覧を見る", "adv");
                command(holder, 13, Material.GOLDEN_SHOVEL, "土地を守る", "保護用シャベルを受け取る", "claimshovel");
                command(holder, 14, Material.CLOCK, "ログインボーナス", "今月の進み具合を見る", "daily");
                command(holder, 15, Material.PLAYER_HEAD, "友達招待", "招待コード・実績を見る", "invite");
                command(holder, 16, Material.PAINTING, "ポスター", "画像を選んで飾る", "poster");
                command(holder, 17, Material.OAK_DOOR, "案内所", "初心者向けの5項目", "menu");
                command(holder, 18, Material.EMERALD_ORE, "今日の職業クエスト", "仕事の目標を確認する", "jobs quests");
            }
            case HELP -> {
                command(holder, 10, Material.BOOK, "遊び方", "最初にやることを確認", "guide");
                command(holder, 11, Material.WRITTEN_BOOK, "ルール", "サーバーのルールを確認", "rules");
                help(holder, 12, Material.FEATHER, "運営へ伝える", "要望・不具合を送る", "/feedback <内容>");
                help(holder, 13, Material.CHEST, "ショップの作り方", "チェスト・樽を左クリック", "ショップ作成は100S。/qs browse で一覧");
                help(holder, 14, Material.GOLDEN_SHOVEL, "土地保護の使い方", "金のシャベルで範囲を選ぶ", "/claimshovel で受け取れます");
                command(holder, 15, Material.OAK_DOOR, "案内所", "ワールド移動・職業・目標", "menu");
            }
            case SET_HOME -> {
                command(holder, 13, Material.GREEN_WOOL, "登録する", "現在地をホームに登録（100S）", "sethome");
                page(holder, 15, Material.BARRIER, "戻る", "登録せず移動画面へ", Page.TRAVEL);
            }
        }
        if (page != Page.HOME && page != Page.SET_HOME)
            page(holder, 22, Material.ARROW, "トップへ戻る", "スマホの最初の画面", Page.HOME);
        player.openInventory(holder.inventory);
    }

    private String title(Page page) {
        return switch (page) {
            case HOME -> "ホーム"; case TRADE -> "売り買い"; case TRAVEL -> "移動";
            case PLAY -> "遊びと記録"; case HELP -> "案内と相談"; case SET_HOME -> "ホーム登録の確認";
        };
    }

    private void item(PhoneMenu menu, int slot, Material material, String title, String lore,
                      Consumer<Player> action) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.AQUA));
        meta.lore(List.of(Component.text(lore, NamedTextColor.GRAY)));
        stack.setItemMeta(meta);
        menu.inventory.setItem(slot, stack);
        if (action != null) menu.actions.put(slot, action);
    }

    private void command(PhoneMenu menu, int slot, Material material, String title, String lore, String command) {
        item(menu, slot, material, title, lore + "  /" + command,
                player -> { if (!player.performCommand(command)) player.sendMessage("この機能は現在利用できません。 /" + command); });
    }

    private void page(PhoneMenu menu, int slot, Material material, String title, String lore, Page page) {
        item(menu, slot, material, title, lore, player -> open(player, page));
    }

    private void help(PhoneMenu menu, int slot, Material material, String title, String lore, String help) {
        item(menu, slot, material, title, lore, player -> player.sendMessage(title + ": " + help));
    }
}
