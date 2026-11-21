package labs.algo.backtracking;

import java.util.regex.Pattern;

/**
 * java.util.regex 是回溯实现的。(a+)+b 匹配 "aaa…ac" 时，n 个 a 有 2^(n-1) 种切法，回溯要全试一遍才认输。
 * JDK 8 的 Pattern 真的会全试；JDK 11 起（源码里的 Loop.posIndex / localTCNCount）记下“从位置 i 开始已经失败过”，
 * 第二次走到就直接认输，于是不再指数爆炸。但正则里只要有反向引用（\1），这个优化就关掉。
 */
public class RegexBacktrack {
    public static void main(String[] args) {
        String[] patterns = {"(a+)+b", "(x?)(a+)+b\\1", "a+b"};
        for (String p : patterns) {
            Pattern pat = Pattern.compile(p);
            for (int n = 16; n <= 26; n += 2) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) sb.append('a');
                String s = sb.append('c').toString();
                long t0 = System.nanoTime();
                boolean m = pat.matcher(s).matches();
                double ms = (System.nanoTime() - t0) / 1e6;
                System.out.printf("%-14s n=%d  %s  %8.1f ms%n", p, n, m, ms);
                if (ms > 5000) break;
            }
        }
    }
}
