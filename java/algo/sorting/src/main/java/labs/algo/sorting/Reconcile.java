package labs.algo.sorting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 夜间对账：撮合流水和账务流水各一份，按成交号排序，两根指针一起走。
 * 同一成交号可能有两条：先“成交”、后“更正”，排完必须保持这个先后。
 */
public class Reconcile {

    static final class Rec {
        final long tradeId; final String kind; final long amount;
        Rec(long tradeId, String kind, long amount) { this.tradeId = tradeId; this.kind = kind; this.amount = amount; }
        public String toString() { return tradeId + ":" + kind + ":" + amount; }
    }

    static final Comparator<Rec> BY_ID = Comparator.comparingLong(r -> r.tradeId);

    /** 两份都按成交号排好以后，一遍走完，找出对不上的。 */
    static List<String> diff(Rec[] match, Rec[] ledger) {
        List<String> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < match.length || j < ledger.length) {
            if (j == ledger.length || (i < match.length && match[i].tradeId < ledger[j].tradeId)) { out.add("账务缺 " + match[i++]); }
            else if (i == match.length || ledger[j].tradeId < match[i].tradeId) { out.add("撮合缺 " + ledger[j++]); }
            else { if (match[i].amount != ledger[j].amount) out.add("金额不一致 " + match[i] + " vs " + ledger[j]); i++; j++; }
        }
        return out;
    }

    public static void main(String[] args) {
        Rec[] match = {
            new Rec(105, "成交", 300), new Rec(101, "成交", 200), new Rec(103, "成交", 500),
            new Rec(102, "成交", 100), new Rec(103, "更正", 480), new Rec(104, "成交", 700)};
        Rec[] ledger = {
            new Rec(101, "成交", 200), new Rec(103, "成交", 500), new Rec(103, "更正", 480),
            new Rec(105, "成交", 300), new Rec(102, "成交", 110), new Rec(106, "成交", 900)};

        Rec[] m1 = match.clone(), l1 = ledger.clone();
        Arrays.sort(m1, BY_ID);            // 对象数组：TimSort，稳定
        Arrays.sort(l1, BY_ID);
        System.out.println("撮合（TimSort）：" + Arrays.toString(m1));
        System.out.println("账务（TimSort）：" + Arrays.toString(l1));
        diff(m1, l1).forEach(s -> System.out.println("  " + s));

        Rec[] m2 = match.clone();
        Sorts.quickSortObj(m2, 0, m2.length - 1, BY_ID);     // 不稳定
        System.out.println("撮合（不稳定快排）：" + Arrays.toString(m2));
        System.out.println("  同号 103 的两条：" + (m2[2].kind.equals("成交") ? "成交在前（这次碰巧没乱）" : "更正跑到了成交前面"));
        diff(m2, l1).forEach(x -> System.out.println("  " + x));
    }
}
