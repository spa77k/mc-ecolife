package dev.spa.ecolife;

/** notify.sources 1件ぶんの設定。 */
record NotifySource(boolean enabled, String eventClassName, String template,
                    long minIntervalSeconds, long perPlayerCooldownSeconds) {
}
