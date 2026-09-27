package dev.spa.ecolife;

import java.util.List;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** config.yml の automation-watch: セクションを読んだ結果。読み込み後は変わらない。 */
record AutomationConfig(
        boolean enabled,
        String webhookUrl,
        String username,
        String avatarUrl,
        int checkIntervalSeconds,
        int radiusBlocks,
        int afkSeconds,
        int windowMinutes,
        int minActiveMinutes,
        int minScore,
        int[] weights,
        Set<String> excludedWorlds) {

    static AutomationConfig load(JavaPlugin plugin) {
        FileConfiguration c = plugin.getConfig();
        String base = "automation-watch.";
        int window = Math.max(1, c.getInt(base + "window-minutes", 10));
        int[] weights = new int[AutomationWatch.Kind.values().length];
        for (AutomationWatch.Kind kind : AutomationWatch.Kind.values()) {
            weights[kind.ordinal()] = Math.max(0, c.getInt(base + "weights." + kind.key, kind.defaultWeight));
        }
        List<String> excluded = c.getStringList(base + "excluded-worlds");
        String url = c.getString(base + "webhook-url", "");
        AutomationConfig result = new AutomationConfig(
                c.getBoolean(base + "enabled", false),
                url == null ? "" : url,
                c.getString(base + "username", ""),
                c.getString(base + "avatar-url", ""),
                Math.max(1, c.getInt(base + "check-interval-seconds", 60)),
                Math.max(1, c.getInt(base + "radius-blocks", 64)),
                Math.max(1, c.getInt(base + "afk-seconds", 60)),
                window,
                Math.min(window, Math.max(1, c.getInt(base + "min-active-minutes", 8))),
                Math.max(1, c.getInt(base + "min-score", 200)),
                weights,
                Set.copyOf(excluded));
        if (result.enabled() && !result.webhookConfigured()) {
            plugin.getLogger().warning("automation-watch.enabled は true ですが、webhook-url が未設定のため、"
                    + "検出はサーバーログと記録だけに残します。URLを設定すると未送信分を送ります。");
        }
        return result;
    }

    /** URLが空、または ${...} のまま（spsmc-infraの起動時置換が効いていない）なら未設定として扱う。 */
    boolean webhookConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank() && !webhookUrl.trim().startsWith("${");
    }
}
