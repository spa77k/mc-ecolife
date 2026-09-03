package dev.spa.ecolife;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** config.yml を読んだ結果。読み込み後は変わらない。 */
final class BonusConfig {

    private final ZoneId zone;
    private final int resetHour;
    private final boolean enabled;
    private final long claimDelayTicks;
    private final boolean broadcastPerfectMonth;
    private final boolean title;
    private final String soundKey;
    private final RewardTable rewards;

    private BonusConfig(ZoneId zone, int resetHour, boolean enabled, long claimDelayTicks,
                        boolean broadcastPerfectMonth, boolean title, String soundKey, RewardTable rewards) {
        this.zone = zone;
        this.resetHour = resetHour;
        this.enabled = enabled;
        this.claimDelayTicks = claimDelayTicks;
        this.broadcastPerfectMonth = broadcastPerfectMonth;
        this.title = title;
        this.soundKey = soundKey;
        this.rewards = rewards;
    }

    static BonusConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();

        String zoneName = config.getString("day.timezone", "");
        ZoneId zone = ZoneId.systemDefault();
        if (zoneName != null && !zoneName.isBlank()) {
            try {
                zone = ZoneId.of(zoneName.trim());
            } catch (DateTimeException e) {
                plugin.getLogger().warning("day.timezone の " + zoneName
                        + " は解釈できません。サーバーの既定 " + zone + " を使います。");
            }
        }

        return new BonusConfig(
                zone,
                Math.max(0, Math.min(23, config.getInt("day.reset-hour", 4))),
                config.getBoolean("login-bonus.enabled", true),
                Math.max(0L, config.getLong("login-bonus.claim-delay-ticks", 60L)),
                config.getBoolean("login-bonus.broadcast-perfect-month", true),
                config.getBoolean("effects.title", true),
                soundKey(config.getString("effects.sound", "ENTITY_PLAYER_LEVELUP")),
                RewardTable.load(plugin, config.getConfigurationSection("rewards")));
    }

    /** ENTITY_PLAYER_LEVELUP のような書き方を、どのサーバー版でも通る entity.player.levelup へ直す。 */
    private static String soundKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String name = raw.trim();
        if (name.indexOf(':') >= 0 || name.indexOf('.') >= 0) {
            return name.toLowerCase(Locale.ROOT);
        }
        return name.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    /**
     * 「今日」を求める。区切り時刻より前はまだ前日として数えるため、その分だけ時刻を戻してから日付を取る。
     * 例えば区切りが4時なら、9月2日の午前2時は 9月1日 として扱う。月の切り替わりも同じ日付で判定する。
     */
    LocalDate today() {
        return ZonedDateTime.now(zone).minusHours(resetHour).toLocalDate();
    }

    YearMonth currentMonth() {
        return YearMonth.from(today());
    }

    /** 次に日付が変わるまでの残り時間。案内文に使う。 */
    Duration untilNextDay() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime next = now.toLocalDate().atStartOfDay(zone).plusHours(resetHour);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Duration.between(now, next);
    }

    ZoneId zone() {
        return zone;
    }

    int resetHour() {
        return resetHour;
    }

    boolean enabled() {
        return enabled;
    }

    long claimDelayTicks() {
        return claimDelayTicks;
    }

    boolean broadcastPerfectMonth() {
        return broadcastPerfectMonth;
    }

    boolean title() {
        return title;
    }

    String soundKey() {
        return soundKey;
    }

    RewardTable rewards() {
        return rewards;
    }
}
