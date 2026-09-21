package dev.spa.ecolife.invite;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class InviteService implements Listener, CommandExecutor, TabCompleter, AutoCloseable {
    private final JavaPlugin plugin;
    final InviteStore store;
    private final byte[] secret;
    private final InviteGui gui;
    private final McLevelPlaytime playtime;
    private boolean broken;

    public InviteService(JavaPlugin plugin) throws Exception {
        this.plugin = plugin;
        playtime = new McLevelPlaytime(plugin.getLogger());
        store = new InviteStore(plugin.getDataFolder().toPath().resolve("invites.db"));
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        secret =
                HexFormat.of()
                        .parseHex(store.metadata("ip-secret", HexFormat.of().formatHex(seed)));
        store.metadata("introduced-at", Long.toString(System.currentTimeMillis()));
        // Offline names may be stale; only names observed on a live join are accepted as codes.
        // hasPlayedBefore excludes pre-install players even on their first join after installation.
        for (Player p : Bukkit.getOnlinePlayers()) record(p, false);
        gui = new InviteGui(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(gui, plugin);
        PluginCommand command = Objects.requireNonNull(plugin.getCommand("invite"));
        command.setExecutor(this);
        command.setTabCompleter(this);
        Bukkit.getScheduler()
                .runTaskTimer(
                        plugin,
                        () -> {
                            if (!enabled()) return;
                            for (Player player : Bukkit.getOnlinePlayers())
                                try {
                                    check(player);
                                } catch (Exception e) {
                                    fail(e);
                                    break;
                                }
                        },
                        20L,
                        200L);
    }

    boolean enabled() {
        return !broken && plugin.getConfig().getBoolean("invite.enabled", true);
    }

    private long threshold() {
        double hours = plugin.getConfig().getDouble("invite.required-hours", 2);
        if (!Double.isFinite(hours) || hours <= 0 || hours * 3600 >= Long.MAX_VALUE)
            throw new IllegalArgumentException(
                    "invite.required-hours must be positive and fit active seconds");
        return (long) Math.ceil(hours * 3600);
    }

    private double amount(String key, double fallback) {
        double amount = plugin.getConfig().getDouble("invite.rewards." + key, fallback);
        if (!Double.isFinite(amount) || amount < 0)
            throw new IllegalArgumentException("Invalid invite reward: " + key);
        return amount;
    }

    public void validate() {
        threshold();
        amount("inviter", 2000);
        amount("newcomer", 1000);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        if (broken) return;
        Player p = event.getPlayer();
        try {
            record(p, !p.hasPlayedBefore());
        } catch (Exception e) {
            fail(e);
        }
    }

    private void record(Player p, boolean eligible) throws Exception {
        String hash = null;
        if (p.getAddress() != null && p.getAddress().getAddress() != null) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            hash = HexFormat.of().formatHex(mac.doFinal(p.getAddress().getAddress().getAddress()));
        }
        store.person(p.getUniqueId(), p.getName(), eligible, hash);
    }

    Component text(String key, Object... pairs) {
        String raw = plugin.getConfig().getString("invite.messages." + key, key);
        for (int i = 0; i < pairs.length; i += 2)
            raw = raw.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }

    void say(CommandSender sender, String key, Object... pairs) {
        sender.sendMessage(text(key, pairs));
    }

    String name(UUID id) throws SQLException {
        InviteStore.Person person = store.person(id);
        return person == null ? id.toString() : person.name();
    }

    Component state(InviteStore.Link l) {
        return text("state-" + l.state().toLowerCase(Locale.ROOT));
    }

    private void emit(InviteEvent.Kind kind, InviteStore.Link link) {
        Bukkit.getPluginManager().callEvent(new InviteEvent(kind, link));
    }

    private boolean economyAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("Vault") && VaultPayments.available();
    }

    private boolean bind(
            CommandSender sender, Player newcomer, InviteStore.Person inviter, boolean override)
            throws Exception {
        UUID id = newcomer.getUniqueId();
        InviteStore.Person person = store.person(id);
        if (person == null || !person.eligible()) {
            say(sender, "not-new");
            return false;
        }
        OptionalLong activeSeconds = playtime.seconds(newcomer);
        if (activeSeconds.isEmpty()) {
            say(sender, "playtime-unavailable");
            return false;
        }
        if (activeSeconds.getAsLong() >= threshold()) {
            say(sender, "too-late");
            return false;
        }
        if (inviter == null) {
            say(sender, "unknown");
            return false;
        }
        if (inviter.id().equals(id)) {
            say(sender, "self");
            return false;
        }
        InviteStore.Link old = store.link(id);
        if (old != null && !old.state().equals("CANCELLED")) {
            say(sender, "already");
            return false;
        }
        // Prevent reciprocal invitations/cycles, including manual links.
        Set<UUID> seen = new HashSet<>();
        UUID cursor = inviter.id();
        while (seen.add(cursor)) {
            if (cursor.equals(id)) {
                say(sender, "cycle");
                return false;
            }
            var parent = store.link(cursor);
            if (parent == null || parent.state().equals("CANCELLED")) break;
            cursor = parent.inviter();
        }
        store.bind(
                id,
                inviter.id(),
                override,
                amount("inviter", 2000),
                amount("newcomer", 1000),
                sender.getName());
        emit(InviteEvent.Kind.LINKED, store.link(id));
        say(sender, "linked", "name", inviter.name());
        return true;
    }

    public void check(Player newcomer) throws Exception {
        if (!enabled()) return;
        InviteStore.Link l = store.link(newcomer.getUniqueId());
        if (l == null) return;
        // Resume invitations held by the former IP restriction without changing payment records.
        if (l.state().equals("BLOCKED")) {
            store.state(l.newcomer(), "WAITING");
            l = store.link(l.newcomer());
        }
        if (!l.state().equals("WAITING")) return;
        OptionalLong activeSeconds = playtime.seconds(newcomer);
        if (activeSeconds.isEmpty() || activeSeconds.getAsLong() < threshold()) return;
        if (!economyAvailable()) return;
        if (l.inviterPay().equals("SENDING") || l.newcomerPay().equals("SENDING")) return;
        // Both attempts run in this server tick. Vault has no multi-account transaction API.
        if (!pay(l, true)) return;
        if (!pay(store.link(l.newcomer()), false)) return;
        if (store.complete(l.newcomer())) {
            emit(InviteEvent.Kind.COMPLETED, store.link(l.newcomer()));
            say(newcomer, "paid", "amount", l.newcomerAmount());
            Player inviter = Bukkit.getPlayer(l.inviter());
            if (inviter != null) say(inviter, "paid", "amount", l.inviterAmount());
        }
    }

    private boolean pay(InviteStore.Link link, boolean inviter) throws SQLException {
        String status = inviter ? link.inviterPay() : link.newcomerPay();
        if (status.equals("PAID")) return true;
        if (!status.equals("PENDING")) return false;
        double value = inviter ? link.inviterAmount() : link.newcomerAmount();
        store.payment(link.newcomer(), inviter, "SENDING");
        try {
            if (value > 0) {
                if (!VaultPayments.deposit(inviter ? link.inviter() : link.newcomer(), value)) {
                    // Provider failures can be ambiguous. Operator checks actual balance before
                    // resolution.
                    plugin.getLogger()
                            .warning(
                                    "招待報酬の確認が必要です: "
                                            + link.newcomer()
                                            + " / "
                                            + (inviter ? "inviter" : "newcomer"));
                    return false;
                }
            }
            store.payment(link.newcomer(), inviter, "PAID");
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().severe("招待報酬の結果が不明です。/invite admin resolve で確認: " + link.newcomer());
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ecolife.invite")) {
            say(sender, "no-permission");
            return true;
        }
        if (broken) {
            say(sender, "unavailable");
            return true;
        }
        try {
            if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
                admin(sender, args);
                return true;
            }
            if (!enabled()) {
                say(sender, "disabled");
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("top")) {
                say(sender, "top-title");
                int rank = 1;
                for (var row : store.top(0).stream().limit(10).toList())
                    say(sender, "rank", "rank", rank++, "name", row.name(), "count", row.count());
                return true;
            }
            if (!(sender instanceof Player player)) {
                say(sender, "player-only");
                return true;
            }
            if (args.length == 0) {
                gui.open(player, false, 0);
                return true;
            }
            if (args.length == 1) {
                bind(sender, player, store.find(args[0]), false);
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("code")) {
                bind(sender, player, store.find(args[1]), false);
                return true;
            }
            say(sender, "usage");
        } catch (Exception e) {
            fail(e);
            say(sender, "unavailable");
        }
        return true;
    }

    private void admin(CommandSender sender, String[] args) throws Exception {
        if (!sender.hasPermission("ecolife.admin")) {
            say(sender, "no-permission");
            return;
        }
        if (args.length == 2 && args[1].equals("status")) {
            say(sender, "status", "enabled", enabled(), "vault", economyAvailable());
            return;
        }
        if (args.length < 3) {
            say(sender, "admin-usage");
            return;
        }
        InviteStore.Person p = store.find(args[2]);
        if (p == null) {
            say(sender, "unknown");
            return;
        }
        InviteStore.Link link = store.link(p.id());
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "info" -> {
                say(sender, "person-info", "name", p.name(), "eligible", p.eligible());
                if (link == null) {
                    say(sender, "no-link");
                    return;
                }
                say(
                        sender,
                        "info",
                        "name",
                        name(link.inviter()),
                        "state",
                        link.state(),
                        "inviterPay",
                        link.inviterPay(),
                        "newcomerPay",
                        link.newcomerPay(),
                        "override",
                        link.overrideIp());
            }
            case "link" -> {
                if (args.length != 4) {
                    say(sender, "admin-usage");
                    return;
                }
                Player player = Bukkit.getPlayer(p.id());
                if (player == null) {
                    say(sender, "online-required");
                    return;
                }
                bind(sender, player, store.find(args[3]), true);
            }
            case "allowip" -> {
                if (link == null
                        || !(link.state().equals("WAITING") || link.state().equals("BLOCKED"))) {
                    say(sender, "no-link");
                    return;
                }
                store.override(p.id(), sender.getName());
                say(sender, "admin-done");
            }
            case "cancel" -> {
                if (store.cancel(p.id(), sender.getName())) {
                    emit(InviteEvent.Kind.CANCELLED, store.link(p.id()));
                    say(sender, "admin-done");
                } else say(sender, "cannot-cancel");
            }
            case "resolve" -> {
                if (args.length != 5
                        || !Set.of("inviter", "newcomer").contains(args[3])
                        || !Set.of("paid", "unpaid").contains(args[4])) {
                    say(sender, "admin-usage");
                    return;
                }
                boolean side = args[3].equals("inviter");
                if (link == null
                        || !(side ? link.inviterPay() : link.newcomerPay()).equals("SENDING")) {
                    say(sender, "not-uncertain");
                    return;
                }
                store.resolve(p.id(), side, args[4].equals("paid"), sender.getName());
                say(sender, "admin-done");
            }
            default -> say(sender, "admin-usage");
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command c, String label, String[] args) {
        if (args.length == 1)
            return java.util.stream.Stream.concat(
                            java.util.stream.Stream.of("top", "code"),
                            Bukkit.getOnlinePlayers().stream().map(Player::getName))
                    .filter(
                            s ->
                                    s.toLowerCase(Locale.ROOT)
                                            .startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        if (args.length == 2
                && args[0].equalsIgnoreCase("admin")
                && sender.hasPermission("ecolife.admin"))
            return List.of("status", "info", "link", "allowip", "cancel", "resolve");
        return List.of();
    }

    void fail(Exception e) {
        broken = true;
        plugin.getLogger()
                .log(java.util.logging.Level.SEVERE, "招待機能を停止しました。ログとDBを確認して再起動してください。", e);
    }

    @Override
    public void close() throws SQLException {
        store.close();
    }
}
