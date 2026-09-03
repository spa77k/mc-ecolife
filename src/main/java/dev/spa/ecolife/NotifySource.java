package dev.spa.ecolife;

/** notify.sources 1件ぶんの設定。 */
record NotifySource(String key, boolean enabled, String eventClassName, String template,
                    long minIntervalSeconds, long perPlayerCooldownSeconds) {
}
