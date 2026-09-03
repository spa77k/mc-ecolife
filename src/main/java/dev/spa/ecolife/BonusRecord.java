package dev.spa.ecolife;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * プレイヤー1人ぶんの受け取り記録。
 *
 * <p>「今月のマス」と「これまでの記録」は別の数字である。マスは月が変わると1に戻るが、
 * 連続ログインは月をまたいでも続く。そのため連続の起点には {@code lastClaim} ではなく
 * {@code streakDay} を使う。{@code lastClaim} を起点にすると、過去のログイン記録から
 * 遡って入れた連続日数が、初回の受け取りで1に潰れてしまう。
 *
 * @param name         表示用に残しておく最後の名前
 * @param month        いま数えている月。月が変わるとマスは1つ目に戻る
 * @param monthClaims  その月に受け取った回数。そのままカレンダーの何マス目かを表す
 * @param lastClaim    最後に受け取った日（区切り時刻を考慮した論理日）。未受け取りなら null
 * @param totalClaims  累計ログイン日数として見せている、通算の受け取り回数
 * @param perfectMonth 月の日数ぶんすべて受け取った月の数
 * @param streak       いま続いている連続ログイン日数
 * @param bestStreak   これまでの最長連続ログイン日数
 * @param streakDay    連続を最後に伸ばした論理日。連続判定はこの日だけを見る
 */
record BonusRecord(String name, YearMonth month, int monthClaims, LocalDate lastClaim,
                   int totalClaims, int perfectMonth,
                   int streak, int bestStreak, LocalDate streakDay) {

    static final BonusRecord EMPTY = new BonusRecord("", null, 0, null, 0, 0, 0, 0, null);

    /** 今その月の何マス目まで進んでいるか。月が変わっていれば0から数え直す。 */
    int slotsIn(YearMonth current) {
        return current.equals(month) ? monthClaims : 0;
    }

    /** 最長記録を出すのは、いまの連続がそれに届いていないときだけ。 */
    boolean showsBestStreak() {
        return bestStreak > streak;
    }

    /** その日ぶんを受け取ったあとの連続日数。前日から続いていれば伸び、空けば1に戻る。 */
    int streakAfter(LocalDate day) {
        if (day.equals(streakDay)) {
            return Math.max(streak, 1);
        }
        if (streakDay != null && streakDay.plusDays(1).equals(day)) {
            return streak + 1;
        }
        return 1;
    }

    BonusRecord claimed(String playerName, YearMonth current, int slot, LocalDate day, boolean perfect) {
        int nextStreak = streakAfter(day);
        return new BonusRecord(playerName, current, slot, day, totalClaims + 1,
                perfect ? perfectMonth + 1 : perfectMonth,
                nextStreak, Math.max(bestStreak, nextStreak), day);
    }
}
