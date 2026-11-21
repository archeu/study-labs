package labs.algo.kmp_trie;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 第 23 课 · Trie。全景第 1 站网关：用户输入 "BT"，补全出 BTC-USDT、BTC-USDC……
 * 对比三种做法：全表扫 startsWith、TreeMap 前缀区间（第 14 课的红黑树）、Trie。
 */
public class SymbolTrie {

    static final class Node {
        // 交易对只有 A-Z、0-9 和 '-'，共 37 种字符；用数组按字符下标找孩子
        final Node[] next = new Node[37];
        String word;            // 走到这里正好是一个完整代码时，记下它
    }

    static int idx(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= '0' && c <= '9') return 26 + c - '0';
        if (c == '-') return 36;
        throw new IllegalArgumentException("不支持的字符 " + c);
    }

    final Node root = new Node();
    int nodes = 1;

    void insert(String s) {
        Node cur = root;
        for (int i = 0; i < s.length(); i++) {
            int k = idx(s.charAt(i));
            if (cur.next[k] == null) { cur.next[k] = new Node(); nodes++; }
            cur = cur.next[k];
        }
        cur.word = s;
    }

    /** 先沿前缀走到节点，再在子树里按字典序 DFS，收集最多 limit 个。 */
    List<String> complete(String prefix, int limit) {
        Node cur = root;
        for (int i = 0; i < prefix.length() && cur != null; i++) cur = cur.next[idx(prefix.charAt(i))];
        List<String> out = new ArrayList<String>();
        if (cur != null) dfs(cur, out, limit);
        return out;
    }

    private void dfs(Node n, List<String> out, int limit) {
        if (out.size() >= limit) return;
        if (n.word != null) out.add(n.word);
        for (Node c : n.next) if (c != null) dfs(c, out, limit);
    }

    static List<String> scan(List<String> all, String prefix, int limit) {
        List<String> out = new ArrayList<String>();
        for (String s : all) if (s.startsWith(prefix)) { out.add(s); if (out.size() >= limit) break; }
        return out;
    }

    static List<String> treeRange(TreeMap<String, Boolean> map, String prefix, int limit) {
        List<String> out = new ArrayList<String>();
        // 前缀区间：[prefix, prefix + 最大字符)
        for (Map.Entry<String, Boolean> e : map.subMap(prefix, true, prefix + Character.MAX_VALUE, false).entrySet()) {
            out.add(e.getKey());
            if (out.size() >= limit) break;
        }
        return out;
    }

    public static void main(String[] args) {
        String[] demo = {"BTC-USDT", "BTC-USDC", "BCH-USDT", "BNB-USDT", "ETH-USDT", "ETH-BTC", "BTC-EUR"};
        SymbolTrie t = new SymbolTrie();
        for (String s : demo) t.insert(s);
        System.out.println("7 个交易对建成 Trie：" + t.nodes + " 个节点（7 个代码共 " + total(demo) + " 个字符，公共前缀共用节点）");
        System.out.println("输入 B   → " + t.complete("B", 10));
        System.out.println("输入 BT  → " + t.complete("BT", 10));
        System.out.println("输入 BTC-U → " + t.complete("BTC-U", 10));
        System.out.println("输入 X   → " + t.complete("X", 10));

        // 规模：5 万个代码（随机基础币 × 计价币），10 万次补全查询
        Random r = new Random(23);
        List<String> all = new ArrayList<String>();
        String[] quotes = {"USDT", "USDC", "BTC", "ETH", "EUR"};
        java.util.Set<String> seen = new java.util.HashSet<String>();
        while (all.size() < 50000) {
            StringBuilder b = new StringBuilder();
            int len = 2 + r.nextInt(4);
            for (int i = 0; i < len; i++) b.append((char) ('A' + r.nextInt(26)));
            String s = b + "-" + quotes[r.nextInt(quotes.length)];
            if (seen.add(s)) all.add(s);
        }
        SymbolTrie big = new SymbolTrie();
        TreeMap<String, Boolean> tree = new TreeMap<String, Boolean>();
        for (String s : all) { big.insert(s); tree.put(s, Boolean.TRUE); }
        String[] qs = new String[100000];
        for (int i = 0; i < qs.length; i++) { String s = all.get(r.nextInt(all.size())); qs[i] = s.substring(0, 1 + r.nextInt(3)); }
        for (int round = 1; round <= 2; round++) {
            long h = 0, t0 = System.nanoTime();
            for (String q : qs) h += scan(all, q, 10).size();
            long t1 = System.nanoTime();
            for (String q : qs) h += treeRange(tree, q, 10).size();
            long t2 = System.nanoTime();
            for (String q : qs) h += big.complete(q, 10).size();
            long t3 = System.nanoTime();
            System.out.printf("第 %d 轮 5 万个代码 × 10 万次补全（取前 10 个）：全表扫 %d ms，TreeMap 区间 %d ms，Trie %d ms（校验 %d）%n",
                    round, (t1 - t0) / 1000000, (t2 - t1) / 1000000, (t3 - t2) / 1000000, h);
        }
        System.out.println("Trie 节点数 " + big.nodes + "，每个节点一个 37 格数组");
        System.out.println("最坏前缀：输入 \"Q-\"（没有以 Q- 开头的）全表扫要比完 5 万个，Trie 走两步就知道没有：" + big.complete("Q-", 10));
    }

    static int total(String[] a) { int n = 0; for (String s : a) n += s.length(); return n; }
}
