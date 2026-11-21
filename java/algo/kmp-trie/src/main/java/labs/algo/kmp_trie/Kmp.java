package labs.algo.kmp_trie;

import java.util.Arrays;

/**
 * 第 23 课 · KMP。在一段报文流 / 日志里找一个模式串。
 * 1) next 数组（最长相等前后缀）怎么来
 * 2) 最坏输入下朴素匹配 vs KMP 的比较次数
 * 3) 和 JDK 的 String.indexOf（朴素算法 + JIT 内建优化）比耗时
 */
public class Kmp {

    static long cmp;

    /** next[j] = pattern[0..j] 这一段里，最长的“既是前缀又是后缀”的长度（不含整段本身）。 */
    static int[] next(String p) {
        int[] nx = new int[p.length()];
        for (int i = 1, k = 0; i < p.length(); i++) {
            while (k > 0 && p.charAt(i) != p.charAt(k)) k = nx[k - 1];
            if (p.charAt(i) == p.charAt(k)) k++;
            nx[i] = k;
        }
        return nx;
    }

    static int kmp(String t, String p) {
        int[] nx = next(p);
        for (int i = 0, j = 0; i < t.length(); i++) {
            while (j > 0 && t.charAt(i) != p.charAt(j)) { cmp++; j = nx[j - 1]; }  // 失配：主串不回退，模式串跳
            cmp++;
            if (t.charAt(i) == p.charAt(j)) j++;
            if (j == p.length()) return i - j + 1;
        }
        return -1;
    }

    static int naive(String t, String p) {
        for (int i = 0; i + p.length() <= t.length(); i++) {
            int j = 0;
            while (j < p.length()) { cmp++; if (t.charAt(i + j) != p.charAt(j)) break; j++; }
            if (j == p.length()) return i;
        }
        return -1;
    }

    static String rep(char c, int n) { char[] a = new char[n]; Arrays.fill(a, c); return new String(a); }

    public static void main(String[] args) {
        for (String p : new String[]{"ABABC", "AAAAB", "c-7781"})
            System.out.println("next(\"" + p + "\") = " + Arrays.toString(next(p)));

        String t = "ABABABCA", p = "ABABC";
        cmp = 0; int a = naive(t, p); long c1 = cmp;
        cmp = 0; int b = kmp(t, p); long c2 = cmp;
        System.out.println("在 " + t + " 里找 " + p + "：朴素 位置 " + a + " 比较 " + c1 + " 次；KMP 位置 " + b + " 比较 " + c2 + " 次");

        // 最坏输入：主串 n 个 A，模式串 m-1 个 A 加一个 B
        for (int[] nm : new int[][]{{100000, 100}, {1000000, 1000}}) {
            int n = nm[0], m = nm[1];
            String text = rep('A', n), pat = rep('A', m - 1) + "B";
            cmp = 0; naive(text, pat); long cn = cmp;
            cmp = 0; kmp(text, pat); long ck = cmp;
            System.out.println("n=" + n + " m=" + m + "（最坏）：朴素比较 " + cn + " 次，KMP " + ck + " 次");
        }

        // 耗时：同一份最坏输入，String.indexOf vs 手写 KMP；再看一份正常的随机日志
        String text = rep('A', 1000000), pat = rep('A', 999) + "B";
        java.util.Random r = new java.util.Random(23);
        StringBuilder log = new StringBuilder();
        while (log.length() < 1000000) log.append("BUY,BTC-USDT,").append(10000 + r.nextInt(500)).append(",c-").append(r.nextInt(9000)).append('\n');
        String normal = log.toString(), needle = "c-77810";
        for (int round = 1; round <= 3; round++) {
            long t0 = System.nanoTime(); int x = text.indexOf(pat);
            long t1 = System.nanoTime(); int y = kmp(text, pat);
            long t2 = System.nanoTime(); int u = normal.indexOf(needle);
            long t3 = System.nanoTime(); int v = kmp(normal, needle);
            long t4 = System.nanoTime();
            System.out.printf("第 %d 轮：最坏输入 indexOf %d ms / KMP %d ms；正常日志 indexOf %.2f ms / KMP %.2f ms（%d %d %d %d）%n",
                    round, (t1 - t0) / 1000000, (t2 - t1) / 1000000, (t3 - t2) / 1e6, (t4 - t3) / 1e6, x, y, u, v);
        }
    }
}
