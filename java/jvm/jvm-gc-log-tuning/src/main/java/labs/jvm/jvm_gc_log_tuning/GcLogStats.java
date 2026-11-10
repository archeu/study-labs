package labs.jvm.jvm_gc_log_tuning;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/**
 * 读 JDK 9+ 统一日志格式的 GC 日志（-Xlog:gc 或 gc*，默认装饰 [uptime][level][tags]），
 * 只看每次停顿的那一行，算出调优要对照的几样数：
 *   GC 时间占比、最长停顿、停顿 p99、各种停顿各几次、分配速率、GC 后堆占用（≈ 活对象）的走势。
 *
 *   java GcLogStats gc.log [最长停顿目标ms=50] [GC时间占比目标%=5] [从第几秒算起=0，用来跳过启动预热]
 */
public class GcLogStats {
    // [12.345s][info][gc] GC(17) Pause Young (Normal) (G1 Evacuation Pause) 150M->20M(256M) 3.123ms
    static final Pattern PAUSE = Pattern.compile(
        "\\[(\\d+\\.\\d+)s\\].*\\[gc\\s*\\] GC\\((\\d+)\\) (Pause \\w+(?: \\([^)]*\\))*) (\\d+)M->(\\d+)M\\((\\d+)M\\) (\\d+\\.\\d+)ms");
    static final Pattern TIME = Pattern.compile("^\\[(\\d+\\.\\d+)s\\]");

    public static void main(String[] args) throws IOException {
        double targetMs = args.length > 1 ? Double.parseDouble(args[1]) : 50;
        double targetPct = args.length > 2 ? Double.parseDouble(args[2]) : 5;
        double from = args.length > 3 ? Double.parseDouble(args[3]) : 0;
        List<double[]> p = new ArrayList<double[]>();       // {time, before, after, cap, ms, 是不是 Full GC}
        Map<String, Integer> kinds = new LinkedHashMap<String, Integer>();
        double end = 0;
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            Matcher t = TIME.matcher(line);
            if (t.find()) end = Double.parseDouble(t.group(1));
            Matcher m = PAUSE.matcher(line);
            if (!m.find() || Double.parseDouble(m.group(1)) < from) continue;
            String kind = m.group(3).replace(" (G1 Evacuation Pause)", "").replace(" (G1 Humongous Allocation)", "")
                .replaceAll(" \\((Allocation Failure|Ergonomics|System.gc\\(\\)|G1 Compaction Pause|Heap Inspection Initiated GC)\\)", "");
            kinds.put(kind, kinds.containsKey(kind) ? kinds.get(kind) + 1 : 1);
            p.add(new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(4)), Double.parseDouble(m.group(5)),
                Double.parseDouble(m.group(6)), Double.parseDouble(m.group(7)), kind.startsWith("Pause Full") ? 1 : 0});
        }
        in.close();
        if (p.isEmpty()) { System.out.println("没找到停顿行：要用 -Xlog:gc 或 -Xlog:gc*，并保留默认的 uptime 装饰"); return; }

        double total = 0, max = 0, alloc = 0;
        double[] ms = new double[p.size()];
        for (int i = 0; i < p.size(); i++) {
            double[] x = p.get(i);
            ms[i] = x[4]; total += x[4]; max = Math.max(max, x[4]);
            if (i > 0) alloc += Math.max(0, x[1] - p.get(i - 1)[2]);    // 两次 GC 之间新分配的
        }
        Arrays.sort(ms);
        double span = p.get(p.size() - 1)[0] - p.get(0)[0];
        double pct = total / 1000 / (end - from) * 100;
        int half = p.size() / 2;
        double minAfter = Double.MAX_VALUE, a1 = 0, a2 = 0, cap = p.get(p.size() - 1)[3];
        for (int i = 0; i < p.size(); i++) {
            double after = p.get(i)[2];
            minAfter = Math.min(minAfter, after);
            if (i < half) a1 += after; else a2 += after;
        }
        a1 /= Math.max(1, half); a2 /= (p.size() - half);
        int full = 0, fullStillFull = 0;                    // Full GC 次数；其中回收后仍占堆 90% 以上的次数
        for (double[] x : p) if (x[5] == 1 && x[2] > x[3] * 0.9) fullStillFull++;
        for (Map.Entry<String, Integer> e : kinds.entrySet()) if (e.getKey().startsWith("Pause Full")) full += e.getValue();
        double lastAfter = p.get(p.size() - 1)[2];

        System.out.printf("统计 %.1f～%.1f 秒，停顿 %d 次，合计 %.0f ms%n", from, end, p.size(), total);
        for (Map.Entry<String, Integer> e : kinds.entrySet()) System.out.printf("  %-34s %d 次%n", e.getKey(), e.getValue());
        System.out.printf("GC 时间占比 %.1f%%（目标 ≤ %.0f%%）%n", pct, targetPct);
        System.out.printf("停顿：p50 %.1f ms，p99 %.1f ms，最长 %.1f ms（目标 ≤ %.0f ms）%n",
            ms[ms.length / 2], ms[Math.min(ms.length - 1, (int) (ms.length * 0.99))], max, targetMs);
        System.out.printf("分配速率约 %.0f MB/s%n", span > 0 ? alloc / span : 0);
        System.out.printf("GC 后堆占用：最低 %.0f MB，前一半平均 %.0f MB，后一半平均 %.0f MB，最后 %.0f MB，堆上限 %.0f MB%n",
            minAfter, a1, a2, lastAfter, cap);

        String verdict;
        if (full > 0 && fullStillFull * 2 >= full) verdict = "Full GC 之后堆还是满的：回收不掉，是代码问题（泄漏或活对象太多），先做堆转储，别动 GC 参数";
        else if (a2 > a1 * 1.3 && a2 > cap * 0.5) verdict = "GC 后的占用一路上涨：疑似泄漏，盯一段时间的直方图再下结论";
        else if (full > 0) verdict = "有 Full GC，但回收后降下来了：堆相对活对象太小，或晋升太快，先看堆是不是活对象的 3 倍以上";
        else if (max > targetMs || pct > targetPct) verdict = "没有 Full GC，但没达标：按差的那一项调（停顿超了看每次复制多少活对象，占比超了加大堆或新生代）";
        else verdict = "达标：别动参数";
        System.out.println("结论：" + verdict);
    }
}
