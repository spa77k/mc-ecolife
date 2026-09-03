package dev.spa.ecolife;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * プレイヤー1人ぶんの受け取り記録。
 *
 * @param name         表示用に残しておく最後の名前
 * @param month        いま数えている月。月が変わるとマスは1つ目に戻る
 * @param monthClaims  その月に受け取った回数。そのままカレンダーの何マス目かを表す
 * @param lastClaim    最後に受け取った日（区切り時刻を考慮した論理日）。未受け取りなら null
 * @param totalClaims  通算の受け取り回数
 * @param perfectMonth 月の日数ぶんすべて受け取った月の数
 */
record BonusRecord(String name, YearMonth month, int monthClaims, LocalDate lastClaim,
                   int totalClaims, int perfectMonth) {

    static final BonusRecord EMPTY = new BonusRecord("", null, 0, null, 0, 0);

    /** 今その月の何マス目まで進んでいるか。月が変わっていれば0から数え直す。 */
    int slotsIn(YearMonth current) {
        return current.equals(month) ? monthClaims : 0;
    }

    BonusRecord claimed(String playerName, YearMonth current, int slot, LocalDate day, boolean perfect) {
        return new BonusRecord(playerName, current, slot, day, totalClaims + 1,
                perfect ? perfectMonth + 1 : perfectMonth);
    }
}
