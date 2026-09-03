package dev.spa.ecolife;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/** 発生元プラグインのイベントを、コンパイル時依存を持たずに動的購読する。 */
final class NotifyBridge {

    private final JavaPlugin plugin;
    private final NotifyService service;
    private final Logger logger;

    /** 購読キー→状態（購読中・未導入・型が不正 など）。/ecolife notify status で見せる。 */
    private final Map<String, String> subscriptionStatus = new LinkedHashMap<>();
    /** キーごとに実際に登録したリスナー。個別に購読解除するため、共有せず1件ずつ作る。 */
    private final Map<String, Listener> activeListeners = new ConcurrentHashMap<>();
    /** getNotifyKind/getNotifyPlaceholdersを持たないと分かった型。二重に警告しないための記録。 */
    private final Set<Class<?>> warnedBrokenTypes = ConcurrentHashMap.newKeySet();

    NotifyBridge(JavaPlugin plugin, NotifyService service) {
        this.plugin = plugin;
        this.service = service;
        this.logger = plugin.getLogger();
    }

    /** config の sources を走査し、有効なものだけ動的に購読する。呼ぶ前に unregisterAll しておくこと。 */
    void registerAll(Map<String, NotifySource> sources) {
        subscriptionStatus.clear();
        for (Map.Entry<String, NotifySource> entry : sources.entrySet()) {
            String key = entry.getKey();
            NotifySource source = entry.getValue();
            if (!source.enabled()) {
                subscriptionStatus.put(key, "設定で無効");
                continue;
            }
            register(key, source);
        }
    }

    @SuppressWarnings("unchecked")
    private void register(String key, NotifySource source) {
        Class<?> eventClass;
        try {
            eventClass = Class.forName(source.eventClassName());
        } catch (ClassNotFoundException e) {
            logger.info("通知 " + key + " の発生元プラグインが見つからないため、購読を見送ります（"
                    + source.eventClassName() + "）。");
            subscriptionStatus.put(key, "未導入");
            return;
        }
        if (!Event.class.isAssignableFrom(eventClass)) {
            logger.warning("通知 " + key + " の " + source.eventClassName() + " はイベント型ではありません。購読しません。");
            subscriptionStatus.put(key, "型が不正");
            return;
        }

        Listener listener = new Listener() {
        };
        EventExecutor executor = (l, event) -> handle(key, source, event);
        try {
            plugin.getServer().getPluginManager().registerEvent(
                    (Class<? extends Event>) eventClass, listener, EventPriority.MONITOR, executor, plugin, true);
        } catch (RuntimeException e) {
            logger.warning("通知 " + key + " の " + source.eventClassName()
                    + " を購読できませんでした（HandlerListが規約通りではない可能性があります）: " + e.getMessage());
            subscriptionStatus.put(key, "型が不正");
            return;
        }
        activeListeners.put(key, listener);
        subscriptionStatus.put(key, "購読中");
    }

    @SuppressWarnings("unchecked")
    private void handle(String key, NotifySource source, Event event) {
        Class<?> type = event.getClass();
        if (warnedBrokenTypes.contains(type)) {
            return;
        }
        String kind;
        Map<String, String> placeholders;
        try {
            Method kindMethod = type.getMethod("getNotifyKind");
            Method placeholdersMethod = type.getMethod("getNotifyPlaceholders");
            kind = (String) kindMethod.invoke(event);
            placeholders = (Map<String, String>) placeholdersMethod.invoke(event);
        } catch (NoSuchMethodException e) {
            warnBroken(key, type, "getNotifyKind/getNotifyPlaceholders を持っていません");
            return;
        } catch (ReflectiveOperationException | ClassCastException e) {
            warnBroken(key, type, "通知情報の取得に失敗しました: " + e.getMessage());
            return;
        }
        service.notify(key, source, kind, placeholders);
    }

    /** 規約を満たさない型を検出したら、1回だけ警告してその購読を止める。 */
    private void warnBroken(String key, Class<?> type, String reason) {
        warnedBrokenTypes.add(type);
        logger.warning(type.getName() + " は通知規約を満たしていないため（" + reason
                + "）、通知 " + key + " の購読をこれ以降止めます。");
        Listener listener = activeListeners.remove(key);
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
        subscriptionStatus.put(key, "型が不正のため停止");
    }

    /** 現在の購読状況のスナップショット。 */
    Map<String, String> subscriptionStatus() {
        return Map.copyOf(subscriptionStatus);
    }

    /** 登録した購読をすべて解除する。reload や onDisable の前に呼ぶ。 */
    void unregisterAll() {
        for (Listener listener : activeListeners.values()) {
            HandlerList.unregisterAll(listener);
        }
        activeListeners.clear();
        subscriptionStatus.clear();
    }
}
