package dev.spa.ecolife.loan;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.java.JavaPlugin;

/** 隔離Paperと本物のEssentialsX・Vaultで借金を検証する。Playerだけテスト用アダプタ。 */
public final class PaperLoanProbe extends JavaPlugin {
    private static final long DAY = 86_400_000L;

    public static final class ShopHolder implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    public static final class OtherHolder implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private Economy economy;
    private Player player;
    private PluginCommand command;
    private LoanService service;
    private LoanBook book;
    private UUID id;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                run();
                getLogger().info("LOAN_PROBE_PASS");
            } catch (Throwable error) {
                getLogger().log(java.util.logging.Level.SEVERE, "LOAN_PROBE_FAIL", error);
            }
            Bukkit.shutdown();
        }, 60L);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private void money(String expected, String message) {
        BigDecimal actual = BigDecimal.valueOf(economy.getBalance(player));
        check(actual.compareTo(new BigDecimal(expected)) == 0, message + ": balance " + actual + ", expected " + expected);
    }

    private void owed(String expected, String message) {
        check(book.owed(id).compareTo(new BigDecimal(expected)) == 0, message + ": owed " + book.owed(id) + ", expected " + expected);
    }

    private void loan(String... args) {
        check(command.execute(player, "loan", args), "command handled");
    }

    private boolean commandBlocked(String message) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, message);
        service.onCommand(event);
        return event.isCancelled();
    }

    private boolean openBlocked(InventoryHolder holder, Inventory inventory) {
        InventoryView view = (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[]{InventoryView.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getTopInventory" -> inventory;
                    case "getPlayer" -> player;
                    default -> null;
                });
        InventoryOpenEvent event = new InventoryOpenEvent(view);
        service.onInventoryOpen(event);
        return event.isCancelled();
    }

    private void run() throws Exception {
        JavaPlugin eco = (JavaPlugin) Bukkit.getPluginManager().getPlugin("EcoLifeAssist");
        check(eco != null && eco.isEnabled(), "EcoLifeAssist enabled");
        command = Bukkit.getPluginCommand("ecolifeassist:loan");
        check(command != null && command.getPlugin() == eco, "EcoLifeAssist owns /loan");
        var serviceField = eco.getClass().getDeclaredField("loans");
        serviceField.setAccessible(true);
        service = (LoanService) serviceField.get(eco);
        var bookField = LoanService.class.getDeclaredField("book");
        bookField.setAccessible(true);
        book = (LoanBook) bookField.get(service);
        Method tick = LoanService.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        economy = Bukkit.getServicesManager().getRegistration(Economy.class).getProvider();
        id = UUID.nameUUIDFromBytes("loan-test-player".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "LoanProbe";
                    case "isOnline" -> true;
                    case "getServer" -> Bukkit.getServer();
                    // Lv2相当。上限は1万S。
                    case "hasPermission" -> "ecolife.loan".equals(args[0]) || "group.mclevel_lv2".equals(args[0]);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> id.hashCode();
                    default -> {
                        Class<?> type = method.getReturnType();
                        yield type == boolean.class ? false : type == int.class ? 0 : null;
                    }
                });

        if (Boolean.getBoolean("probe.restart")) {
            owed("300", "loan survives restart");
            money("560", "balance survives restart");
            loan("repay", "all");
            owed("0", "repaid after restart");
            return;
        }

        money("0", "initial balance");
        check(service.summary(player).getFirst().contains("ありません"), "summary without loan");
        loan("borrow", "20000");
        owed("0", "over the Lv2 limit is refused");
        loan("borrow", "1.5");
        owed("0", "fractions are refused");
        loan("borrow", "5000");
        owed("5000", "borrowed");
        money("5000", "borrowed money arrives");
        loan("borrow", "5001");
        owed("5000", "limit counts the existing loan");
        loan("repay", "1000");
        owed("4000", "partial repayment");
        money("4000", "repayment is withdrawn");

        // 期限内はペナルティなし。
        check(economy.depositPlayer(player, 100).transactionSuccess(), "income");
        money("4100", "no garnish before the deadline");
        check(!commandBlocked("/pay Friend 1"), "pay allowed before the deadline");
        ShopHolder shop = new ShopHolder();
        shop.inventory = Bukkit.createInventory(shop, 9);
        check(!openBlocked(shop, shop.inventory), "shop opens before the deadline");

        // 15日前に借りたことにする。利息2回ぶん（4000 × 1.1 × 1.1）と期限切れ。
        long past = System.currentTimeMillis() - 15 * DAY;
        book.put(id, new LoanBook.Loan("LoanProbe", new BigDecimal("4000"), past, past));
        tick.invoke(service);
        owed("4840", "two weeks of compound interest");
        check(service.overdue(id), "overdue after 14 days");

        check(economy.depositPlayer(player, 1000).transactionSuccess(), "income while overdue");
        money("4600", "half of the income is garnished");
        owed("4340", "garnished income repays the loan");
        check(economy.withdrawPlayer(player, 100).transactionSuccess(), "spending still works at the economy level");
        money("4500", "spending is not garnished");
        owed("4340", "spending does not change the loan");

        check(commandBlocked("/pay Friend 1"), "pay blocked while overdue");
        check(commandBlocked("/irai create"), "contract creation blocked while overdue");
        check(!commandBlocked("/irai"), "contract board still opens");
        check(!commandBlocked("/loan repay all"), "repayment command allowed");
        check(openBlocked(shop, shop.inventory), "shop blocked while overdue");
        OtherHolder other = new OtherHolder();
        other.inventory = Bukkit.createInventory(other, 9);
        check(!openBlocked(other, other.inventory), "other screens stay open");
        loan("borrow", "100");
        owed("4340", "no new loan while overdue");

        loan("repay", "all");
        owed("0", "repay all");
        money("160", "repay all withdraws the loan only");
        check(!service.overdue(id), "penalty ends with the loan");
        check(!commandBlocked("/pay Friend 1"), "pay allowed after repayment");
        check(economy.depositPlayer(player, 100).transactionSuccess(), "income after repayment");
        money("260", "no garnish after repayment");

        loan("borrow", "300");
        owed("300", "loan for the restart check");
        money("560", "balance for the restart check");
    }
}
