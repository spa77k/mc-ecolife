package dev.spa.ecolife;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** config.yml やコード内に書いた &コード付きの文字列を Adventure の Component へ直す。 */
final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    static Component of(String legacy) {
        return LEGACY.deserialize(legacy);
    }

    static Component prefixed(String legacy) {
        return LEGACY.deserialize("&8[&aエコライフ&8] &r" + legacy);
    }
}
