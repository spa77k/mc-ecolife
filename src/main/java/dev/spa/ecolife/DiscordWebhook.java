package dev.spa.ecolife;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Discord Webhook へ POST する。URLはログにも例外メッセージにも一切出さない。 */
final class DiscordWebhook {

    /** ログに出すときの、Webhook URLの代わりの表記。実URLの断片は含めない。 */
    private static final String MASKED_URL = ".../xxxx";
    private static final Pattern RETRY_AFTER_BODY = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9.]+)");
    private static final long DEFAULT_RETRY_AFTER_MILLIS = 1000L;
    private static final long MAX_RETRY_WAIT_MILLIS = 10_000L;

    private enum Result { OK, RETRY, GIVE_UP }

    private final HttpClient client;
    private final Duration requestTimeout;
    private final Logger logger;

    DiscordWebhook(Duration connectTimeout, Duration requestTimeout, Logger logger) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
        this.logger = logger;
    }

    /** 送信する。呼び出し側の非同期スレッドから呼ぶこと（このメソッド自体はブロッキング）。送信できたら true。 */
    boolean send(String webhookUrl, String username, String avatarUrl, String content) {
        String payload = buildPayload(username, avatarUrl, content);
        Result first = attempt(webhookUrl, payload);
        if (first == Result.OK) {
            return true;
        }
        if (first == Result.GIVE_UP) {
            return false;
        }
        Result second = attempt(webhookUrl, payload);
        if (second != Result.OK) {
            logger.warning("Discord通知を1回再送しましたが失敗したため諦めます（" + MASKED_URL + "）。");
            return false;
        }
        return true;
    }

    private Result attempt(String webhookUrl, String payload) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            logger.warning("Discord通知のWebhook URL（" + MASKED_URL + "）が不正なため送信を諦めます。");
            return Result.GIVE_UP;
        }

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.GIVE_UP;
        } catch (IOException e) {
            logger.warning("Discord通知の送信に失敗しました（" + MASKED_URL + "）: " + e.getClass().getSimpleName());
            return Result.RETRY;
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return Result.OK;
        }
        if (status == 429) {
            sleep(retryAfterMillis(response));
            return Result.RETRY;
        }
        if (status >= 500) {
            return Result.RETRY;
        }
        logger.warning("Discord通知が失敗しました（" + MASKED_URL + "）: HTTP " + status + "。再送はせず破棄します。");
        return Result.GIVE_UP;
    }

    /** Retry-Afterヘッダ、無ければ本文の retry_after を見る。どちらも無ければ既定値を待つ。 */
    private long retryAfterMillis(HttpResponse<String> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isPresent()) {
            try {
                return (long) (Double.parseDouble(header.get().trim()) * 1000L);
            } catch (NumberFormatException ignored) {
                // 本文の解析へフォールバックする。
            }
        }
        String body = response.body();
        if (body != null) {
            Matcher matcher = RETRY_AFTER_BODY.matcher(body);
            if (matcher.find()) {
                try {
                    return (long) (Double.parseDouble(matcher.group(1)) * 1000L);
                } catch (NumberFormatException ignored) {
                    // 既定値へフォールバックする。
                }
            }
        }
        return DEFAULT_RETRY_AFTER_MILLIS;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0L, Math.min(millis, MAX_RETRY_WAIT_MILLIS)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String buildPayload(String username, String avatarUrl, String content) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"content\":\"").append(escapeJson(content)).append('"');
        if (username != null && !username.isBlank()) {
            json.append(",\"username\":\"").append(escapeJson(username)).append('"');
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            json.append(",\"avatar_url\":\"").append(escapeJson(avatarUrl)).append('"');
        }
        json.append(",\"allowed_mentions\":{\"parse\":[]}");
        json.append('}');
        return json.toString();
    }

    private static String escapeJson(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
