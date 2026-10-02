package dev.spa.ecolife;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** 段階ごとの候補から、その月の各マスの中身を選ぶ。 */
final class MonthlyDraw {

    private MonthlyDraw() {
    }

    /** 1つの段階。slots のマスに、candidates の中から1つずつ置く。 */
    record Pool<T>(String name, List<Integer> slots, List<T> candidates) {
    }

    /**
     * 同じ段階の中では、候補を使い切るまで同じものを2回選ばない。
     * 候補がマスより少ない段階だけ、使い切ったあとに混ぜ直してもう一巡する。
     */
    static <T> Map<Integer, T> draw(List<Pool<T>> pools, Random random) {
        Map<Integer, T> result = new TreeMap<>();
        for (Pool<T> pool : pools) {
            if (pool.candidates().isEmpty()) {
                continue;
            }
            List<T> bag = new ArrayList<>();
            for (int slot : pool.slots()) {
                if (bag.isEmpty()) {
                    bag.addAll(pool.candidates());
                    Collections.shuffle(bag, random);
                }
                result.put(slot, bag.removeLast());
            }
        }
        return result;
    }
}
