package dev.spa.ecolife.invite;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.OptionalLong;
import java.util.logging.Logger;

/** Optional McLevel API bridge. Never fall back to vanilla time when unavailable. */
final class McLevelPlaytime {
    private final Logger logger;
    private boolean warned;

    McLevelPlaytime(Logger logger) {
        this.logger = logger;
    }

    OptionalLong seconds(Player player) {
        Plugin provider = Bukkit.getPluginManager().getPlugin("McLevel");
        try {
            if (provider == null || !provider.isEnabled()) return unavailable();
            // Avoid a compile-time dependency while requiring the public, read-only API.
            Object value =
                    provider.getClass().getMethod("getActiveSeconds", Player.class)
                            .invoke(provider, player);
            if (!(value instanceof Long seconds) || seconds < 0) return unavailable();
            if (warned) logger.info("McLevelのアクティブ時間取得が復旧しました。");
            warned = false;
            return OptionalLong.of(seconds);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return unavailable();
        }
    }

    private OptionalLong unavailable() {
        if (!warned) {
            logger.warning(
                    "McLevelのアクティブ時間を取得できないため、招待登録・報酬支払いを保留します。"
                            + "McLevelの導入・更新・稼働状態を確認してください。");
            warned = true;
        }
        return OptionalLong.empty();
    }
}
