package dev.spa.ecolife;

import java.util.regex.Pattern;

/** Discordへ出す前に、プレイヤー入力（依頼タイトル・アイテム名など）を無害化する。 */
final class NotifyText {

    private static final Pattern COLOR_CODE = Pattern.compile("[§&][0-9A-Fa-fK-Ok-orRxX]");
    private static final String MARKDOWN_SYMBOLS = "*_`~|>";

    private NotifyText() {
    }

    /** 色コード除去 → 改行除去 → 長さ制限 → Markdownエスケープ、の順にかける。 */
    static String sanitize(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String stripped = COLOR_CODE.matcher(raw).replaceAll("");
        stripped = stripped.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
        stripped = truncate(stripped, Math.max(0, maxLength));
        return escapeMarkdown(stripped);
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        if (maxLength == 0) {
            return "…";
        }
        return text.substring(0, maxLength) + "…";
    }

    private static String escapeMarkdown(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (MARKDOWN_SYMBOLS.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
