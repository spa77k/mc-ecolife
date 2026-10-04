package dev.spa.ecolife;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** config.yml の notify: セクションを読んだ結果。読み込み後は変わらない。 */
record NotifyConfig(boolean enabled, String webhookUrl, String username, String avatarUrl,
                    int connectTimeoutSeconds, int requestTimeoutSeconds, int queueMaxSize,
                    int maxPerMinute, int textMaxLength, Map<String, NotifySource> sources) {

    static NotifyConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        boolean enabled = config.getBoolean("notify.enabled", false);
        String webhookUrl = config.getString("notify.webhook-url", "");

        NotifyConfig result = new NotifyConfig(
                enabled,
                webhookUrl == null ? "" : webhookUrl,
                config.getString("notify.username", ""),
                config.getString("notify.avatar-url", ""),
                Math.max(1, config.getInt("notify.connect-timeout-seconds", 5)),
                Math.max(1, config.getInt("notify.request-timeout-seconds", 10)),
                Math.max(1, config.getInt("notify.queue-max-size", 200)),
                Math.max(1, config.getInt("notify.max-per-minute", 20)),
                Math.max(1, config.getInt("notify.text-max-length", 80)),
                loadSources(plugin, config.getConfigurationSection("notify.sources")));

        if (enabled && !result.webhookConfigured()) {
            plugin.getLogger().warning("notify.enabled は true ですが、notify.webhook-url が未設定"
                    + "（空、または ${...} が置換されないまま）のため、Discord通知は送信しません。");
        }
        return result;
    }

    private static Map<String, NotifySource> loadSources(JavaPlugin plugin, ConfigurationSection section) {
        Map<String, NotifySource> sources = new LinkedHashMap<>();
        if (section == null) {
            return sources;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            String eventClassName = s.getString("event", "");
            if (eventClassName == null || eventClassName.isBlank()) {
                plugin.getLogger().warning("notify.sources." + key + " に event が設定されていないため読み飛ばします。");
                continue;
            }
            sources.put(key, new NotifySource(
                    s.getBoolean("enabled", true),
                    eventClassName.trim(),
                    s.getString("template", ""),
                    Math.max(0L, s.getLong("min-interval-seconds", 0L)),
                    Math.max(0L, s.getLong("per-player-cooldown-seconds", 0L))));
        }
        return sources;
    }

    /** URLが空、または ${...} のまま（spsmc-infraの起動時置換が効いていない）なら未設定として扱う。 */
    boolean webhookConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank() && !webhookUrl.trim().startsWith("${");
    }
}
