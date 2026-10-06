package dev.spa.ecolife.loan;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 借金。1週間を1年とする複利の利息を付け、返済期限を過ぎた人には収入の差し押さえと買い物・送金の禁止をかける。
 * EssentialsX と QuickShop のイベントは、どちらも無い環境で起動できるようクラス名で購読する。
 */
public final class LoanService implements Listener, CommandExecutor, TabCompleter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"));
    private static final long TICK_PERIOD = 1200L;

    private final JavaPlugin plugin;
    private final File file;
    private final LoanBook book = new LoanBook();
    /** 外部プラグインのイベント購読だけを束ねる。reload で外して張り直す。 */
    private final Listener hooks = new Listener() {};
    private volatile LoanConfig config;
    private volatile boolean dirty;
    private BukkitTask task;

    public LoanService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "loans.yml");
        this.config = LoanConfig.load(plugin);
        load();
        hook();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, TICK_PERIOD);
    }

    public void reload() {
        config = LoanConfig.load(plugin);
        HandlerList.unregisterAll(hooks);
        hook();
    }

    public void close() {
        if (task != null) task.cancel();
        HandlerList.unregisterAll(hooks);
        save();
    }

    // ---- 保存 ----

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            // 読めない台帳を空で上書きすると借金が消えるので、起動を止めて気づけるようにする。
            throw new IllegalStateException("loans.yml を読めません", e);
        }
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            book.put(UUID.fromString(key), new LoanBook.Loan(section.getString("name", key),
                    new BigDecimal(section.getString("amount", "0")),
                    section.getLong("started-at"), section.getLong("interest-at")));
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, LoanBook.Loan> entry : book.all().entrySet()) {
            String key = entry.getKey().toString();
            LoanBook.Loan loan = entry.getValue();
            yaml.set(key + ".name", loan.name());
            yaml.set(key + ".amount", loan.amount().toPlainString());
            yaml.set(key + ".started-at", loan.startedAt());
            yaml.set(key + ".interest-at", loan.interestAt());
        }
        try {
            plugin.getDataFolder().mkdirs();
            File temp = new File(plugin.getDataFolder(), "loans.yml.tmp");
            yaml.save(temp);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "loans.yml を保存できませんでした", e);
        }
    }

    // ---- 利息 ----

    private void tick() {
        LoanConfig config = this.config;
        if (config.enabled()) {
            for (LoanBook.Accrued accrued : book.accrue(System.currentTimeMillis(), config.interestRate(), config.periodMillis())) {
                dirty = true;
                plugin.getLogger().info("LOAN_INTEREST " + accrued.id() + " " + accrued.before().toPlainString()
                        + " -> " + accrued.after().toPlainString());
                Player player = Bukkit.getPlayer(accrued.id());
                if (player != null)
                    send(player, "&c利息が付きました: " + money(accrued.before()) + " → " + money(accrued.after()));
            }
        }
        if (dirty) save();
    }

    public boolean overdue(UUID id) {
        LoanConfig config = this.config;
        return config.enabled() && book.overdue(id, System.currentTimeMillis(), config.dueMillis());
    }

    private BigDecimal limit(Player player) {
        BigDecimal limit = config.limits().get("default");
        for (Map.Entry<String, BigDecimal> entry : config.limits().entrySet()) {
            if (!entry.getKey().equals("default") && entry.getValue().compareTo(limit) > 0
                    && player.hasPermission("group." + entry.getKey())) limit = entry.getValue();
        }
        return limit;
    }

    /** スマホの画面に出す、今の借金の要約。 */
    public List<String> summary(Player player) {
        LoanConfig config = this.config;
        LoanBook.Loan loan = book.get(player.getUniqueId());
        List<String> lines = new ArrayList<>();
        if (loan == null) {
            lines.add("借金はありません");
        } else {
            lines.add("借金残高: " + money(loan.amount()));
            lines.add("次の利息: " + DATE.format(Instant.ofEpochMilli(loan.interestAt() + config.periodMillis())));
            lines.add("返済期限: " + DATE.format(Instant.ofEpochMilli(loan.startedAt() + config.dueMillis()))
                    + (overdue(player.getUniqueId()) ? "（期限切れ）" : ""));
        }
        lines.add("借入上限: " + money(limit(player)) + " / 年利" + percent(config.interestRate()) + "（1週間=1年）");
        return lines;
    }

    // ---- 期限切れのペナルティ ----

    private void hook() {
        if (!config.enabled()) return;
        subscribe("Essentials", "net.ess3.api.events.UserBalanceUpdateEvent", EventPriority.HIGHEST, this::onBalance);
        if (config.blockQuickShop())
            subscribe("QuickShop-Hikari", "com.ghostchu.quickshop.api.event.economy.ShopPurchaseEvent",
                    EventPriority.LOWEST, this::onQuickShopPurchase);
    }

    private interface Handler {
        void handle(Event event) throws ReflectiveOperationException;
    }

    private void subscribe(String owner, String className, EventPriority priority, Handler handler) {
        Plugin provider = Bukkit.getPluginManager().getPlugin(owner);
        if (provider == null) {
            plugin.getLogger().warning(owner + " がないため、借金の一部のペナルティは働きません。");
            return;
        }
        try {
            Class<? extends Event> type = provider.getClass().getClassLoader().loadClass(className).asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(type, hooks, priority, (listener, event) -> {
                if (!type.isInstance(event)) return;
                try {
                    handler.handle(event);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, className + " の処理に失敗しました", e);
                }
            }, plugin, false);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            plugin.getLogger().log(Level.WARNING, owner + " のイベントを購読できませんでした: " + className, e);
        }
    }

    /** 期限切れの間、増えた所持金の一部を返済へ回す。/eco の付与はそのまま通す。 */
    private void onBalance(Event event) throws ReflectiveOperationException {
        if ("COMMAND_ECO".equals(String.valueOf(call(event, "getCause")))) return;
        if (!(call(event, "getPlayer") instanceof Player player)) return;
        UUID id = player.getUniqueId();
        if (!overdue(id)) return;
        BigDecimal after = (BigDecimal) call(event, "getNewBalance");
        BigDecimal taken = book.garnish(id, after.subtract((BigDecimal) call(event, "getOldBalance")), config.garnishRate());
        if (taken.signum() <= 0) return;
        event.getClass().getMethod("setNewBalance", BigDecimal.class).invoke(event, after.subtract(taken));
        dirty = true;
        BigDecimal left = book.owed(id);
        plugin.getLogger().info("LOAN_GARNISH " + id + " " + taken.toPlainString() + " left=" + left.toPlainString());
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online != null) send(online, "&c収入から " + money(taken) + " を差し押さえて返済に回しました。"
                    + (left.signum() == 0 ? "&a借金を完済しました！" : "&7残り " + money(left)));
        });
    }

    private void onQuickShopPurchase(Event event) throws ReflectiveOperationException {
        if (event instanceof Cancellable cancellable && cancellable.isCancelled()) return;
        if (!Boolean.TRUE.equals(call(call(event, "getShop"), "isSelling"))) return;
        if (!(call(call(event, "getPurchaser"), "getBukkitPlayer") instanceof Optional<?> optional)
                || !(optional.orElse(null) instanceof Player player) || !overdue(player.getUniqueId())) return;
        event.getClass().getMethod("setCancelled", boolean.class, String.class)
                .invoke(event, true, "返済期限を過ぎた借金があります");
        deny(player);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!overdue(event.getPlayer().getUniqueId()) || !config.blocks(event.getMessage())) return;
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder == null || !config.blockedHolders().contains(holder.getClass().getName())) return;
        if (!(event.getPlayer() instanceof Player player) || !overdue(player.getUniqueId())) return;
        event.setCancelled(true);
        deny(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (book.get(id) == null) return;
        book.rename(id, player.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            LoanBook.Loan loan = book.get(id);
            if (!player.isOnline() || loan == null || !config.enabled()) return;
            if (overdue(id))
                send(player, "&c借金の返済期限を過ぎています。収入の" + percent(config.garnishRate())
                        + "を差し押さえ中で、買い物・送金もできません。&e/loan");
            else send(player, "借金が " + money(loan.amount()) + " あります。&e/loan");
        }, 60L);
    }

    private void deny(Player player) {
        send(player, "&c返済期限を過ぎた借金があるため、買い物・送金はできません。&e/loan repay <額|all>&c で返済してください。");
    }

    // ---- コマンド ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list") || sub.equals("forgive")) {
            if (!sender.hasPermission("ecolife.loan.admin")) send(sender, "&cこの操作をする権限がありません。");
            else if (sub.equals("list")) list(sender);
            else forgive(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("ゲーム内から実行してください。コンソールでは /loan list と /loan forgive <名前> を使えます。");
            return true;
        }
        if (!player.hasPermission("ecolife.loan")) {
            send(player, "&cこの機能を使う権限がありません。");
            return true;
        }
        if (!config.enabled()) {
            send(player, "借金は現在利用できません。");
            return true;
        }
        switch (sub) {
            case "" -> status(player);
            case "borrow" -> borrow(player, args);
            case "repay" -> repay(player, args);
            default -> usage(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("borrow", "repay"));
            if (sender.hasPermission("ecolife.loan.admin")) options.addAll(List.of("list", "forgive"));
            return options.stream().filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("repay")) return List.of("all");
        if (args.length == 2 && args[0].equalsIgnoreCase("forgive") && sender.hasPermission("ecolife.loan.admin"))
            return book.all().values().stream().map(LoanBook.Loan::name).toList();
        return List.of();
    }

    private void usage(CommandSender sender) {
        send(sender, "&e/loan &7借金の確認");
        send(sender, "&e/loan borrow <額> &7借りる");
        send(sender, "&e/loan repay <額|all> &7返す");
    }

    private void status(Player player) {
        for (String line : summary(player)) send(player, line);
        if (overdue(player.getUniqueId())) send(player, "&c期限切れ: 収入の差し押さえと、買い物・送金の禁止がかかっています。");
        usage(player);
    }

    private void borrow(Player player, String[] args) {
        UUID id = player.getUniqueId();
        BigDecimal amount = amount(args);
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
            send(player, "&c使い方: /loan borrow <額>（1以上の整数）");
            return;
        }
        if (overdue(id)) {
            send(player, "&c返済期限を過ぎているため、新しく借りられません。");
            return;
        }
        if (!LoanPayments.available()) {
            send(player, "&c経済機能を利用できないため、借りられません。");
            return;
        }
        BigDecimal limit = limit(player);
        BigDecimal owed = book.owed(id);
        if (owed.add(amount).compareTo(limit) > 0) {
            send(player, "&c借入上限 " + money(limit) + " を超えます。あと " + money(limit.subtract(owed).max(BigDecimal.ZERO)) + " まで借りられます。");
            return;
        }
        if (!LoanPayments.deposit(player, amount)) {
            send(player, "&c入金に失敗しました。借金は増えていません。");
            return;
        }
        LoanBook.Loan loan = book.borrow(id, player.getName(), amount, System.currentTimeMillis());
        save();
        plugin.getLogger().info("LOAN_BORROW " + player.getName() + " " + id + " " + amount.toPlainString());
        send(player, "&a" + money(amount) + " を借りました。&r借金残高: " + money(loan.amount()));
        send(player, "&e返済期限は " + DATE.format(Instant.ofEpochMilli(loan.startedAt() + config.dueMillis()))
                + " です。過ぎると収入の差し押さえと、買い物・送金の禁止がかかります。");
    }

    private void repay(Player player, String[] args) {
        UUID id = player.getUniqueId();
        BigDecimal owed = book.owed(id);
        if (owed.signum() <= 0) {
            send(player, "借金はありません。");
            return;
        }
        BigDecimal requested = args.length >= 2 && args[1].equalsIgnoreCase("all") ? owed : amount(args);
        if (requested == null || requested.signum() <= 0) {
            send(player, "&c使い方: /loan repay <額|all>");
            return;
        }
        if (!LoanPayments.available()) {
            send(player, "&c経済機能を利用できないため、返済できません。");
            return;
        }
        BigDecimal amount = requested.setScale(2, RoundingMode.DOWN).min(owed)
                .min(LoanPayments.balance(player).setScale(2, RoundingMode.DOWN));
        if (amount.signum() <= 0) {
            send(player, "&c返済に使える所持金がありません。");
            return;
        }
        if (!LoanPayments.withdraw(player, amount)) {
            send(player, "&c引き落としに失敗しました。借金は減っていません。");
            return;
        }
        BigDecimal left = book.reduce(id, amount);
        save();
        plugin.getLogger().info("LOAN_REPAY " + player.getName() + " " + id + " " + amount.toPlainString() + " left=" + left.toPlainString());
        send(player, "&a" + money(amount) + " を返済しました。" + (left.signum() == 0 ? "借金を完済しました！" : "&r残り " + money(left)));
    }

    private void list(CommandSender sender) {
        List<Map.Entry<UUID, LoanBook.Loan>> entries = new ArrayList<>(book.all().entrySet());
        if (entries.isEmpty()) {
            send(sender, "借金をしている人はいません。");
            return;
        }
        entries.sort(Comparator.comparing((Map.Entry<UUID, LoanBook.Loan> entry) -> entry.getValue().amount()).reversed());
        for (Map.Entry<UUID, LoanBook.Loan> entry : entries) {
            LoanBook.Loan loan = entry.getValue();
            send(sender, loan.name() + " " + money(loan.amount()) + " 期限 "
                    + DATE.format(Instant.ofEpochMilli(loan.startedAt() + config.dueMillis()))
                    + (overdue(entry.getKey()) ? " &c期限切れ" : ""));
        }
    }

    private void forgive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "&c使い方: /loan forgive <名前>");
            return;
        }
        for (Map.Entry<UUID, LoanBook.Loan> entry : book.all().entrySet()) {
            if (!entry.getValue().name().equalsIgnoreCase(args[1])) continue;
            LoanBook.Loan removed = book.remove(entry.getKey());
            save();
            plugin.getLogger().info("LOAN_FORGIVE " + removed.name() + " " + entry.getKey() + " "
                    + removed.amount().toPlainString() + " by " + sender.getName());
            send(sender, removed.name() + " の借金 " + money(removed.amount()) + " を帳消しにしました。");
            return;
        }
        send(sender, "&c" + args[1] + " の借金は見つかりません。");
    }

    private static BigDecimal amount(String[] args) {
        if (args.length < 2) return null;
        try {
            return new BigDecimal(args[1].replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String money(BigDecimal amount) {
        return new DecimalFormat("#,##0.##").format(amount) + "S";
    }

    private static String percent(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    private static void send(CommandSender sender, String legacy) {
        Component message = LEGACY.deserialize("&8[&6借金&8] &r" + legacy);
        sender.sendMessage(message);
    }
}
