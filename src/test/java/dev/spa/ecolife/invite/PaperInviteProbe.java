package dev.spa.ecolife.invite;

import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * Runs production commands/listeners/GUI with deterministic Player adapters on real Paper + Vault.
 */
public final class PaperInviteProbe extends JavaPlugin implements Listener {
    private int completed;

    @EventHandler
    public void invite(InviteEvent e) {
        if (e.getKind() == InviteEvent.Kind.COMPLETED) completed++;
    }

    @Override
    public void onEnable() {
        Bukkit.getScheduler()
                .runTaskLater(
                        this,
                        () -> {
                            try {
                                run();
                                getLogger().info("INVITE_PROBE_PASS");
                            } catch (Throwable e) {
                                getLogger()
                                        .log(
                                                java.util.logging.Level.SEVERE,
                                                "INVITE_PROBE_FAIL",
                                                e);
                            } finally {
                                Bukkit.shutdown();
                            }
                        },
                        60);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class User {
        final UUID id;
        String name;
        int ticks;
        final boolean old;
        final Player player;
        Inventory inventory;

        User(String name, String ip, boolean old) {
            this.id =
                    UUID.nameUUIDFromBytes(
                            ("OfflinePlayer:" + name)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.name = name;
            this.old = old;
            player =
                    (Player)
                            Proxy.newProxyInstance(
                                    Player.class.getClassLoader(),
                                    new Class[] {Player.class},
                                    (proxy, method, args) ->
                                            switch (method.getName()) {
                                                case "getUniqueId" -> id;
                                                case "getName" -> this.name;
                                                case "hasPlayedBefore" -> old;
                                                case "getAddress" ->
                                                        new InetSocketAddress(ip, 25565);
                                                case "getStatistic" -> ticks;
                                                case "hasPermission", "isOnline", "isValid" -> true;
                                                case "getServer" -> Bukkit.getServer();
                                                case "openInventory" -> {
                                                    inventory = (Inventory) args[0];
                                                    yield null;
                                                }
                                                case "sendMessage", "sendActionBar" -> null;
                                                case "equals" -> proxy == args[0];
                                                case "hashCode" -> id.hashCode();
                                                case "toString" -> name;
                                                default -> {
                                                    Class<?> t = method.getReturnType();
                                                    yield t == boolean.class
                                                            ? false
                                                            : t == int.class
                                                                    ? 0
                                                                    : t == long.class ? 0L : null;
                                                }
                                            });
        }
    }

    private void command(JavaPlugin plugin, CommandSender p, String... args) {
        plugin.getCommand("invite").execute(p, "invite", args);
    }

    private void run() throws Exception {
        JavaPlugin plugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
        require(plugin != null && plugin.isEnabled(), "plugin enabled");
        var field = plugin.getClass().getDeclaredField("invites");
        field.setAccessible(true);
        InviteService service = (InviteService) field.get(plugin);
        var sf = InviteService.class.getDeclaredField("store");
        sf.setAccessible(true);
        InviteStore store = (InviteStore) sf.get(service);
        Bukkit.getPluginManager().registerEvents(this, this);
        User a = new User("ProbeInviter", "127.0.0.2", true),
                b = new User("ProbeNew", "127.0.0.3", false),
                same = new User("ProbeFamily", "127.0.0.2", false),
                old = new User("ProbeOld", "127.0.0.4", true);
        if (store.link(b.id) != null) {
            require(
                    store.link(b.id).state().equals("COMPLETE"),
                    "completed survives server restart");
            require(
                    store.link(same.id).state().equals("WAITING"),
                    "pending survives server restart");
            require(store.link(b.id).inviterPay().equals("PAID"), "payment survives restart");
            require(store.top(0).getFirst().count() == 2, "ranking survives restart");
            return;
        }
        for (User u : List.of(a, b, same, old))
            service.join(new PlayerJoinEvent(u.player, Component.empty()));
        command(plugin, old.player, a.name);
        require(store.link(old.id) == null, "existing player rejected");
        command(plugin, b.player, b.name);
        require(store.link(b.id) == null, "self rejected");
        command(plugin, same.player, a.name);
        require(store.link(same.id) == null, "same IP rejected");
        command(plugin, b.player, a.name);
        require(store.link(b.id) != null, "normal bind");
        command(plugin, b.player, old.name);
        require(store.link(b.id).inviter().equals(a.id), "cannot overwrite");
        b.ticks = 143999;
        service.check(b.player);
        require(store.link(b.id).inviterPay().equals("PENDING"), "under 2h unpaid");
        Economy economy = Bukkit.getServicesManager().getRegistration(Economy.class).getProvider();
        economy.createPlayerAccount(Bukkit.getOfflinePlayer(a.id));
        economy.createPlayerAccount(Bukkit.getOfflinePlayer(b.id));
        double beforeA = economy.getBalance(Bukkit.getOfflinePlayer(a.id)),
                beforeB = economy.getBalance(Bukkit.getOfflinePlayer(b.id));
        b.ticks = 144000;
        service.check(b.player);
        require(store.link(b.id).state().equals("COMPLETE"), "2h completes");
        require(
                economy.getBalance(Bukkit.getOfflinePlayer(a.id)) - beforeA == 2000,
                "inviter receives 2000");
        require(
                economy.getBalance(Bukkit.getOfflinePlayer(b.id)) - beforeB == 1000,
                "new receives 1000");
        service.check(b.player);
        require(completed == 1, "single completion event");
        require(
                economy.getBalance(Bukkit.getOfflinePlayer(a.id)) - beforeA == 2000,
                "no double pay");
        a.name = "RenamedInviter";
        service.join(new PlayerJoinEvent(a.player, Component.empty()));
        require(store.find(a.name).id().equals(a.id), "rename UUID kept");
        command(plugin, a.player);
        require(a.inventory != null && a.inventory.getSize() == 54, "GUI opened");
        require(a.inventory.getItem(0).getType() == Material.EMERALD, "GUI complete invitation");
        require(a.inventory.getItem(47).getType() == Material.NAME_TAG, "GUI code");
        command(plugin, Bukkit.getConsoleSender(), "top");
        require(store.top(0).getFirst().name().equals(a.name), "ranking current name");
        // Manual family link uses online requirement; IP exception logic is exercised in persisted
        // ledger here.
        store.bind(same.id, a.id, true, 2000, 1000, "probe-admin");
        require(store.link(same.id).overrideIp(), "family override stored");
        User late = new User("ProbeLate", "127.0.0.5", false);
        service.join(new PlayerJoinEvent(late.player, Component.empty()));
        late.ticks = 144000;
        command(plugin, late.player, a.name);
        require(store.link(late.id) == null, "late registration rejected");
        User blocked = new User("ProbeBlocked", "127.0.0.6", false);
        service.join(new PlayerJoinEvent(blocked.player, Component.empty()));
        command(plugin, blocked.player, a.name);
        store.person(blocked.id, blocked.name, true, serviceHash(service, "127.0.0.2"));
        blocked.ticks = 144000;
        service.check(blocked.player);
        require(
                store.link(blocked.id).state().equals("BLOCKED"),
                "same IP rechecked before payout");
        command(plugin, Bukkit.getConsoleSender(), "admin", "allowip", blocked.name);
        require(store.link(blocked.id).overrideIp(), "admin allows IP");
        command(plugin, Bukkit.getConsoleSender(), "admin", "cancel", blocked.name);
        require(store.link(blocked.id).state().equals("CANCELLED"), "admin cancel");
        command(plugin, Bukkit.getConsoleSender(), "admin", "cancel", b.name);
        require(store.link(b.id).state().equals("COMPLETE"), "cannot cancel paid");
        User fault = new User("ProbeFailure", "127.0.0.7", false);
        service.join(new PlayerJoinEvent(fault.player, Component.empty()));
        command(plugin, fault.player, a.name);
        fault.ticks = 144000;
        Economy flaky =
                (Economy)
                        Proxy.newProxyInstance(
                                Economy.class.getClassLoader(),
                                new Class[] {Economy.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("depositPlayer")
                                            && args[0] instanceof OfflinePlayer recipient
                                            && recipient.getUniqueId().equals(fault.id)) {
                                        return new net.milkbowl.vault.economy.EconomyResponse(
                                                0,
                                                0,
                                                net.milkbowl.vault.economy.EconomyResponse
                                                        .ResponseType.FAILURE,
                                                "injected");
                                    }
                                    return method.invoke(economy, args);
                                });
        Bukkit.getServicesManager()
                .register(Economy.class, flaky, this, org.bukkit.plugin.ServicePriority.Highest);
        double beforeFailureA = economy.getBalance(Bukkit.getOfflinePlayer(a.id));
        service.check(fault.player);
        require(store.link(fault.id).inviterPay().equals("PAID"), "partial payment recorded");
        require(
                store.link(fault.id).newcomerPay().equals("SENDING"),
                "failed payment held for review");
        service.check(fault.player);
        require(
                economy.getBalance(Bukkit.getOfflinePlayer(a.id)) - beforeFailureA == 2000,
                "partial payment not repeated");
        command(
                plugin,
                Bukkit.getConsoleSender(),
                "admin",
                "resolve",
                fault.name,
                "newcomer",
                "unpaid");
        Bukkit.getServicesManager().unregister(Economy.class, flaky);
        economy.createPlayerAccount(Bukkit.getOfflinePlayer(fault.id));
        service.check(fault.player);
        require(store.link(fault.id).state().equals("COMPLETE"), "resolved payment completes");
        require(
                economy.getBalance(Bukkit.getOfflinePlayer(a.id)) - beforeFailureA == 2000,
                "recovery does not repay inviter");
        plugin.getCommand("ecolife")
                .execute(Bukkit.getConsoleSender(), "ecolife", new String[] {"reload"});
    }

    private static String serviceHash(InviteService s, String ip) throws Exception {
        var f = InviteService.class.getDeclaredField("secret");
        f.setAccessible(true);
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec((byte[]) f.get(s), "HmacSHA256"));
        return HexFormat.of()
                .formatHex(mac.doFinal(java.net.InetAddress.getByName(ip).getAddress()));
    }
}
