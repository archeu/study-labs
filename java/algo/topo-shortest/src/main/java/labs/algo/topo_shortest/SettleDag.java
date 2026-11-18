package labs.algo.topo_shortest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 夜间清算任务的依赖：A → B 表示 A 做完 B 才能开始。Kahn 算法：入度为 0 的先做，做完把它的出边删掉。
 * 输出个数少于任务数，说明有环（互相等对方），必须报错，不能当作“做完了”。
 */
public class SettleDag {
    final Map<String, List<String>> out = new LinkedHashMap<>();
    final Map<String, Integer> indeg = new LinkedHashMap<>();

    void task(String t) { out.putIfAbsent(t, new ArrayList<String>()); indeg.putIfAbsent(t, 0); }

    public void dep(String before, String after) {
        task(before); task(after);
        out.get(before).add(after);
        indeg.put(after, indeg.get(after) + 1);
    }

    /** 返回一个合法的执行顺序；有环时返回的列表比任务少。 */
    public List<String> order(boolean trace) {
        Map<String, Integer> d = new LinkedHashMap<>(indeg);
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : d.entrySet()) if (e.getValue() == 0) ready.offer(e.getKey());
        if (trace) System.out.println("一开始没有前置的：" + ready);
        List<String> res = new ArrayList<>();
        while (!ready.isEmpty()) {
            String t = ready.poll();
            res.add(t);
            List<String> freed = new ArrayList<>();
            for (String nx : out.get(t)) {
                d.put(nx, d.get(nx) - 1);                     // 删掉 t → nx 这条边
                if (d.get(nx) == 0) { ready.offer(nx); freed.add(nx); }
            }
            if (trace) System.out.printf("做 %-6s 放出 %-14s 可做队列 %s%n", t, freed.isEmpty() ? "-" : freed, ready);
        }
        return res;
    }

    public static SettleDag sample() {
        SettleDag g = new SettleDag();
        g.dep("成交汇总", "手续费"); g.dep("成交汇总", "持仓");
        g.dep("手续费", "资金"); g.dep("持仓", "资金"); g.dep("持仓", "保证金");
        g.dep("行情收盘价", "保证金"); g.dep("资金", "风险度"); g.dep("保证金", "风险度");
        g.dep("风险度", "日终报表"); g.dep("资金", "日终报表");
        return g;
    }

    public static void main(String[] args) {
        SettleDag g = sample();
        List<String> o = g.order(true);
        System.out.println("顺序：" + o + "（" + o.size() + "/" + g.indeg.size() + "）");

        g.dep("风险度", "持仓");                               // 有人加了一条“持仓要等风险度”的依赖：成环
        List<String> bad = g.order(false);
        System.out.println("\n加了 风险度 → 持仓 以后：" + bad + "（" + bad.size() + "/" + g.indeg.size() + "）← 少了，说明有环，卡住的任务："
                + remaining(g, bad));
    }

    static List<String> remaining(SettleDag g, List<String> done) {
        List<String> r = new ArrayList<>(g.indeg.keySet()); r.removeAll(done); return r;
    }
}
