package dev.spa.ecolife;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.plugin.java.JavaPlugin;

/** 通知の間引きと送信キューを持つ。実際の送信は専用のデーモンスレッド1本で行う。 */
final class NotifyService {

    /** 文面に差し込むプレースホルダ。1パスで置き換え、差し込んだ値の中の {..} は展開しない。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    /** /ecolife notify status で見せる、稼働状況のスナップショット。 */
    record Status(boolean enabled, boolean urlConfigured, boolean active, long sent, long failed, long dropped) {
    }

    private final Logger logger;

    private final Map<String, Long> lastSentByKind = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSentByPlayer = new ConcurrentHashMap<>();
    private final Deque<Long> recentSends = new ArrayDeque<>();
    private final Object recentSendsLock = new Object();

    private final AtomicLong totalSent = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();
    private final AtomicLong pendingDropLog = new AtomicLong();
    private final AtomicLong lastDropLogMillis = new AtomicLong();

    private volatile NotifyConfig config;
    private volatile DiscordWebhook webhook;
    private volatile BlockingQueue<NotifyMessage> queue;
    private volatile boolean active;
    private volatile boolean running;
    private Thread worker;

    NotifyService(JavaPlugin plugin) {
        this.logger = plugin.getLogger();
    }

    /** enabled かつ webhook-url 設定済みのときだけ、キューと送信スレッドを立てる。 */
    void start(NotifyConfig config) {
        this.config = config;
        this.active = config.enabled() && config.webhookConfigured();
        lastSentByKind.clear();
        lastSentByPlayer.clear();
        synchronized (recentSendsLock) {
            recentSends.clear();
        }
        if (!active) {
            return;
        }
        this.webhook = new DiscordWebhook(
                Duration.ofSeconds(config.connectTimeoutSeconds()),
                Duration.ofSeconds(config.requestTimeoutSeconds()),
                logger);
        this.queue = new ArrayBlockingQueue<>(config.queueMaxSize());
        this.running = true;
        this.worker = new Thread(this::runWorker, "EcoLifeAssist-Notify");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** onDisable / reload の前に呼ぶ。停止を通知し、最大2秒だけ送信スレッドの終了を待つ。 */
    void stop() {
        running = false;
        active = false;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
            try {
                current.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }

    boolean isActive() {
        return active;
    }

    /** 発生元イベントから届いた1件を、間引きを通してからキューに積む。 */
    void notify(String sourceKey, NotifySource source, String kind, Map<String, String> placeholders) {
        if (!active) {
            return;
        }
        long now = System.currentTimeMillis();
        String player = placeholders == null ? null : placeholders.get("player");
        if (isThrottled(sourceKey, source, player, now)) {
            recordDrop();
            return;
        }
        String content = render(source.template(), placeholders);
        if (!queue.offer(new NotifyMessage(content))) {
            recordDrop();
            return;
        }
        markSent(sourceKey, player, now);
    }

    /** テスト送信。間引きの対象外で、1件だけキューに積む。 */
    boolean sendTest() {
        if (!active) {
            return false;
        }
        return queue.offer(new NotifyMessage("🔔 EcoLifeAssist の Discord通知テスト送信です。"));
    }

    Status status() {
        NotifyConfig current = config;
        return new Status(
                current != null && current.enabled(),
                current != null && current.webhookConfigured(),
                active,
                totalSent.get(),
                totalFailed.get(),
                totalDropped.get());
    }

    private boolean isThrottled(String sourceKey, NotifySource source, String player, long now) {
        Long lastKind = lastSentByKind.get(sourceKey);
        if (lastKind != null && now - lastKind < source.minIntervalSeconds() * 1000L) {
            return true;
        }
        if (player != null && source.perPlayerCooldownSeconds() > 0) {
            Long lastPlayer = lastSentByPlayer.get(sourceKey + "|" + player);
            if (lastPlayer != null && now - lastPlayer < source.perPlayerCooldownSeconds() * 1000L) {
                return true;
            }
        }
        return isOverMaxPerMinute(now);
    }

    private boolean isOverMaxPerMinute(long now) {
        synchronized (recentSendsLock) {
            while (!recentSends.isEmpty() && now - recentSends.peekFirst() > 60_000L) {
                recentSends.pollFirst();
            }
            return recentSends.size() >= config.maxPerMinute();
        }
    }

    private void markSent(String sourceKey, String player, long now) {
        lastSentByKind.put(sourceKey, now);
        if (player != null) {
            lastSentByPlayer.put(sourceKey + "|" + player, now);
        }
        synchronized (recentSendsLock) {
            recentSends.addLast(now);
        }
    }

    private String render(String template, Map<String, String> placeholders) {
        if (template == null) {
            return "";
        }
        Map<String, String> values = placeholders == null ? Map.of() : placeholders;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            // 値が無いプレースホルダは残す。設定の書き間違いに気づけるようにするため
            String replacement = value == null ? matcher.group()
                    : NotifyText.sanitize(value, config.textMaxLength());
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 破棄件数を数え、1分に1回以下の頻度でまとめてログに出す。1件ごとには出さない。 */
    private void recordDrop() {
        totalDropped.incrementAndGet();
        pendingDropLog.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastDropLogMillis.get();
        if (now - last >= 60_000L && lastDropLogMillis.compareAndSet(last, now)) {
            long count = pendingDropLog.getAndSet(0);
            if (count > 0) {
                logger.info("Discord通知を間引き・キューあふれで直近に " + count + " 件破棄しました。");
            }
        }
    }

    private void runWorker() {
        while (running) {
            NotifyMessage message;
            try {
                message = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            // 送信できたものだけを送信件数として数える。失敗を成功に混ぜると status が設定調べの役に立たない
            if (webhook.send(config.webhookUrl(), config.username(), config.avatarUrl(), message.content())) {
                totalSent.incrementAndGet();
            } else {
                totalFailed.incrementAndGet();
            }
        }
    }
}
