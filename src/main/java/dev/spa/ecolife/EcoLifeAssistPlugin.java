package dev.spa.ecolife;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** 毎日の暮らしを助ける補助プラグイン。ログインボーナスと、他プラグインの出来事のDiscord通知を持つ。 */
public final class EcoLifeAssistPlugin extends JavaPlugin {

    /** プレイ中に日付が変わった人を拾うための見回り間隔（ティック）。1分。 */
    private static final long ROLLOVER_PERIOD_TICKS = 1200L;

    private BonusConfig bonusConfig;
    private BonusStore store;
    private BonusService bonuses;

    private NotifyConfig notifyConfig;
    private NotifyService notifyService;
    private NotifyBridge notifyBridge;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.bonusConfig = BonusConfig.load(this);
        this.store = BonusStore.open(this);
        this.bonuses = new BonusService(this);

        this.notifyConfig = NotifyConfig.load(this);
        this.notifyService = new NotifyService(this);
        this.notifyBridge = new NotifyBridge(this, notifyService);
        startNotify();

        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        register("daily", new DailyCommand(this), null);
        EcoLifeCommand ecoLifeCommand = new EcoLifeCommand(this);
        register("ecolife", ecoLifeCommand, ecoLifeCommand);

        // 参加時だけだと、日付をまたいで遊び続けている人にその日のぶんが渡らない。
        getServer().getScheduler().runTaskTimer(this, this::grantToOnlinePlayers,
                ROLLOVER_PERIOD_TICKS, ROLLOVER_PERIOD_TICKS);

        getLogger().info("ログインボーナスを読み込みました。区切りは " + bonusConfig.zone()
                + " の " + bonusConfig.resetHour() + "時、報酬表は " + bonusConfig.rewards().configuredDays()
                + " マス、記録は " + store.size() + " 人ぶんです。");
        getLogger().info("Discord通知は " + (notifyService.isActive() ? "有効です。" : "無効です。"));
    }

    @Override
    public void onDisable() {
        if (notifyBridge != null) {
            notifyBridge.unregisterAll();
        }
        if (notifyService != null) {
            notifyService.stop();
        }
        if (store != null) {
            store.saveNow();
        }
    }

    /** notify: の設定に沿って送信サービスを立て、購読できる発生元だけ購読する。 */
    private void startNotify() {
        notifyService.start(notifyConfig);
        if (notifyService.isActive()) {
            notifyBridge.registerAll(notifyConfig.sources());
        }
    }

    private void grantToOnlinePlayers() {
        if (!bonusConfig.enabled()) {
            return;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.hasPermission("ecolife.daily") && bonuses.canClaim(player.getUniqueId())) {
                bonuses.announce(player, bonuses.claim(player));
            }
        }
    }

    private void register(String name, org.bukkit.command.CommandExecutor executor,
                          org.bukkit.command.TabCompleter completer) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("コマンド " + name + " が plugin.yml にありません。");
            return;
        }
        command.setExecutor(executor);
        if (completer != null) {
            command.setTabCompleter(completer);
        }
    }

    /** /ecolife reload から呼ばれる。config.yml を読み直し、通知の購読も張り直す。 */
    void reloadAll() {
        reloadConfig();
        this.bonusConfig = BonusConfig.load(this);

        notifyBridge.unregisterAll();
        notifyService.stop();
        this.notifyConfig = NotifyConfig.load(this);
        startNotify();
    }

    BonusConfig bonusConfig() {
        return bonusConfig;
    }

    BonusStore store() {
        return store;
    }

    BonusService bonuses() {
        return bonuses;
    }

    NotifyConfig notifyConfig() {
        return notifyConfig;
    }

    NotifyService notifyService() {
        return notifyService;
    }

    NotifyBridge notifyBridge() {
        return notifyBridge;
    }
}
