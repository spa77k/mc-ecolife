package dev.spa.ecolife;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.permissions.Permission;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 実PaperとEssentialsXで /home のコマンド衝突解決を確認する。 */
public final class PaperHomeProbe extends JavaPlugin {
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                verify();
                getLogger().info("HOME_PROBE_PASS");
            } catch (Throwable e) {
                getLogger().log(java.util.logging.Level.SEVERE, "HOME_PROBE_FAIL", e);
            } finally {
                Bukkit.shutdown();
            }
        }, 60);
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static Plugin owner(String name) {
        PluginCommand command = Bukkit.getPluginCommand(name);
        return command == null ? null : command.getPlugin();
    }

    @SuppressWarnings("unchecked")
    private void verify() throws Exception {
        Plugin eco = Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
        Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        check(eco != null && eco.isEnabled(), "EcoLifeAssist enabled");
        check(essentials != null && essentials.isEnabled(), "Essentials enabled");
        for (String name : new String[] {"home", "sethome"}) {
            check(owner("ecolifeassist:" + name) == eco, "EcoLifeAssist namespaced " + name);
            check(owner("essentials:" + name) == essentials, "Essentials namespaced " + name);
            Plugin plain = owner(name);
            if (plain == essentials) {
                Object alternatives = essentials.getClass().getMethod("getAlternativeCommandsHandler").invoke(essentials);
                Command alternative = (Command) alternatives.getClass().getMethod("getAlternative", String.class)
                        .invoke(alternatives, name);
                check(alternative instanceof PluginCommand && ((PluginCommand) alternative).getPlugin() == eco,
                        "Essentials delegates " + name);
            } else {
                check(plain == eco, "plain " + name + " owner");
            }
        }
        var settings = ((JavaPlugin) essentials).getConfig();
        check(settings.getInt("sethome-multiple.default") == 2, "two homes configured");
        check(settings.getInt("command-costs.sethome") == 100, "registration price");
        check(settings.getInt("command-costs.home") == 2, "teleport price");

        UUID id = UUID.nameUUIDFromBytes("HomeProbe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "HomeProbe";
                    case "getServer" -> Bukkit.getServer();
                    case "getWorld" -> Bukkit.getWorlds().getFirst();
                    case "getLocation" -> Bukkit.getWorlds().getFirst().getSpawnLocation();
                    case "isOnline", "isValid", "hasPlayedBefore" -> true;
                    case "hasPermission" -> {
                        String node = args[0] instanceof Permission permission ? permission.getName() : String.valueOf(args[0]);
                        yield List.of("ecolife.home", "essentials.home", "essentials.sethome",
                                "essentials.sethome.multiple").contains(node);
                    }
                    case "isPermissionSet" -> List.of("ecolife.home", "essentials.home", "essentials.sethome",
                            "essentials.sethome.multiple", "essentials.nocommandcost.all",
                            "essentials.nocommandcost.home", "essentials.nocommandcost.sethome").contains(String.valueOf(args[0]));
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "HomeProbe";
                    default -> {
                        Class<?> type = method.getReturnType();
                        if (type == boolean.class) yield false;
                        if (type == int.class) yield 0;
                        if (type == long.class) yield 0L;
                        yield null;
                    }
                });
        Object user = essentials.getClass().getMethod("getUser", Player.class).invoke(essentials, player);
        check(user != null, "Essentials user");
        Location location = Bukkit.getWorlds().getFirst().getSpawnLocation();
        user.getClass().getMethod("setHome", String.class, Location.class).invoke(user, "home", location);
        Class<?> bridge = eco.getClass().getClassLoader().loadClass("dev.spa.ecolife.HomeEssentialsBridge");
        var hasHome = bridge.getDeclaredMethod("hasHome", Plugin.class, Player.class, String.class);
        hasHome.setAccessible(true);
        check((boolean) hasHome.invoke(null, essentials, player, "home"), "existing home available");
        check(!(boolean) hasHome.invoke(null, essentials, player, "home2"), "second home absent");

        user.getClass().getMethod("setMoney", BigDecimal.class).invoke(user, BigDecimal.valueOf(1000));
        List<String> visited = new ArrayList<>();
        Class<? extends Event> eventType = (Class<? extends Event>) essentials.getClass().getClassLoader()
                .loadClass("net.ess3.api.events.UserTeleportHomeEvent");
        Bukkit.getPluginManager().registerEvent(eventType, new Listener() {}, EventPriority.LOWEST,
                (listener, event) -> {
                    try {
                        visited.add((String) event.getClass().getMethod("getHomeName").invoke(event));
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                    ((Cancellable) event).setCancelled(true);
                }, this);
        Bukkit.getPluginCommand("home").execute(player, "home", new String[0]);
        check(visited.equals(List.of("home")), "/home selects home1");
        visited.clear();
        user.getClass().getMethod("setHome", String.class, Location.class).invoke(user, "home2", location);
        Bukkit.getPluginCommand("home").execute(player, "home", new String[]{"2"});
        check(visited.equals(List.of("home2")), "/home 2 selects home2");
        visited.clear();
        user.getClass().getMethod("delHome", String.class).invoke(user, "home");
        Bukkit.getPluginCommand("home").execute(player, "home", new String[0]);
        check(visited.isEmpty(), "missing home1 does not redirect to home2");

        user.getClass().getMethod("delHome", String.class).invoke(user, "home2");
        Bukkit.getPluginCommand("sethome").execute(player, "sethome", new String[]{"2"});
        check((boolean) hasHome.invoke(null, essentials, player, "home2"), "/sethome 2 registers home2");
        BigDecimal money = (BigDecimal) user.getClass().getMethod("getMoney").invoke(user);
        Object settingsObject = essentials.getClass().getMethod("getSettings").invoke(essentials);
        BigDecimal cost = (BigDecimal) settingsObject.getClass().getMethod("getCommandCost", String.class)
                .invoke(settingsObject, "sethome");
        boolean bypass = (boolean) user.getClass().getMethod("isAuthorized", String.class)
                .invoke(user, "essentials.nocommandcost.all");
        check(money.compareTo(BigDecimal.valueOf(900)) == 0,
                "registration charges 100: money=" + money + " cost=" + cost + " bypass=" + bypass);
        user.getClass().getMethod("delHome", String.class).invoke(user, "home2");
        user.getClass().getMethod("setMoney", BigDecimal.class).invoke(user, BigDecimal.ZERO);
        Bukkit.getPluginCommand("sethome").execute(player, "sethome", new String[]{"2"});
        check(!(boolean) hasHome.invoke(null, essentials, player, "home2"), "insufficient balance blocks registration");

        user.getClass().getMethod("setMoney", BigDecimal.class).invoke(user, BigDecimal.valueOf(300));
        Bukkit.getPluginCommand("sethome").execute(player, "sethome", new String[0]);
        Bukkit.getPluginCommand("sethome").execute(player, "sethome", new String[]{"2"});
        check((boolean) hasHome.invoke(null, essentials, player, "home"), "home1 registered");
        check((boolean) hasHome.invoke(null, essentials, player, "home2"), "home2 registered");
        check(((List<?>) user.getClass().getMethod("getHomes").invoke(user)).size() == 2, "two home limit");
        Bukkit.getPluginCommand("sethome").execute(player, "sethome", new String[]{"3"});
        check(((BigDecimal) user.getClass().getMethod("getMoney").invoke(user))
                .compareTo(BigDecimal.valueOf(100)) == 0, "invalid slot is free");
        Bukkit.getPluginCommand("sethome").execute(player, "sethome", new String[0]);
        check(((BigDecimal) user.getClass().getMethod("getMoney").invoke(user))
                .compareTo(BigDecimal.ZERO) == 0, "overwrite charges 100");
    }
}
