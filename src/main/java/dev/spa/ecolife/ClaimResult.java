package dev.spa.ecolife;

import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * 受け取りを試した結果。
 *
 * @param status  どうなったか
 * @param slot    カレンダーの何マス目を受け取ったか
 * @param given   渡したアイテム
 * @param dropped 持ち物に入り切らず足元へ落とした個数（スタック単位）
 * @param perfect この受け取りで今月皆勤になったか
 */
record ClaimResult(Status status, int slot, List<ItemStack> given, int dropped, boolean perfect) {

    enum Status {
        /** 受け取れた。 */
        CLAIMED,
        /** 今日のぶんは受け取り済み。 */
        ALREADY_CLAIMED,
        /** 設定で無効になっている。 */
        DISABLED,
        /** そのマスに報酬が設定されていない。マスは進める。 */
        NO_REWARD
    }

    static ClaimResult of(Status status) {
        return new ClaimResult(status, 0, List.of(), 0, false);
    }
}
