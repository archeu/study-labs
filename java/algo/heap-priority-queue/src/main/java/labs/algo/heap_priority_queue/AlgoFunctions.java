package labs.algo.heap_priority_queue;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 算法题里最常用的 Java 函数速查：每一行左边是写法，右边是本机跑出来的结果。
 * 页面 ⑥“常用函数速查”里的结果都来自这里的输出。带“坑”的几行是最容易记错的。
 */
public class AlgoFunctions {
    static void p(String expr, Object result) {
        String r = result instanceof int[] ? Arrays.toString((int[]) result)
                 : result instanceof long[] ? Arrays.toString((long[]) result)
                 : result instanceof char[] ? Arrays.toString((char[]) result)
                 : result instanceof Object[] ? Arrays.deepToString((Object[]) result)
                 : String.valueOf(result);
        System.out.printf("  %-52s → %s%n", expr, r);
    }

    public static void main(String[] args) {
        System.out.println("== 1 数组 Arrays ==");
        int[] a = {5, 2, 8, 1, 9};
        int[] s = a.clone(); Arrays.sort(s);
        p("Arrays.sort(a)  // a = {5,2,8,1,9}", s);
        Integer[] boxed = {5, 2, 8, 1, 9};
        Arrays.sort(boxed, Collections.reverseOrder());
        p("Arrays.sort(Integer[], Collections.reverseOrder())", boxed);
        int[][] iv = {{3, 9}, {1, 4}, {2, 6}};
        Arrays.sort(iv, (x, y) -> Integer.compare(x[0], y[0]));
        p("Arrays.sort(int[][], (x,y)->Integer.compare(x[0],y[0]))", iv);
        int[] f = new int[4]; Arrays.fill(f, -1);
        p("Arrays.fill(new int[4], -1)", f);
        p("Arrays.copyOf(s, 3)", Arrays.copyOf(s, 3));
        p("Arrays.copyOfRange(s, 1, 3)   // [1,3) 左闭右开", Arrays.copyOfRange(s, 1, 3));
        p("Arrays.binarySearch(s, 8)    // s 已排序", Arrays.binarySearch(s, 8));
        p("Arrays.binarySearch(s, 3)    // 没找到", Arrays.binarySearch(s, 3));
        p("Arrays.stream(a).sum() / .max().getAsInt()", Arrays.stream(a).sum() + " / " + Arrays.stream(a).max().getAsInt());
        p("Arrays.equals(new int[]{1,2}, new int[]{1,2})", Arrays.equals(new int[]{1, 2}, new int[]{1, 2}));
        p("new int[]{1,2}.equals(new int[]{1,2})   // 坑", new int[]{1, 2}.equals(new int[]{1, 2}));
        p("Arrays.asList(new int[]{1,2,3}).size()  // 坑", Arrays.asList(new int[]{1, 2, 3}).size());
        p("Arrays.asList(1, 2, 3).size()", Arrays.asList(1, 2, 3).size());
        try { Arrays.asList(1, 2, 3).add(4); }
        catch (UnsupportedOperationException e) { p("Arrays.asList(1,2,3).add(4)   // 坑", "UnsupportedOperationException"); }

        System.out.println("== 2 集合 List / Collections ==");
        List<Integer> list = new ArrayList<>(Arrays.asList(30, 10, 20));
        Collections.sort(list); p("Collections.sort(list) // [30,10,20]", list);
        Collections.reverse(list); p("Collections.reverse(list)", list);
        Collections.swap(list, 0, 2); p("Collections.swap(list, 0, 2)", list);
        p("Collections.max(list) / min(list)", Collections.max(list) + " / " + Collections.min(list));
        List<Integer> l2 = new ArrayList<>(Arrays.asList(10, 20, 30));
        l2.remove(1); p("list.remove(1)   // [10,20,30] 按下标删", l2);
        List<Integer> l3 = new ArrayList<>(Arrays.asList(10, 20, 30));
        l3.remove(Integer.valueOf(10)); p("list.remove(Integer.valueOf(10))  // 按值删", l3);
        list.sort(Comparator.reverseOrder()); p("list.sort(Comparator.reverseOrder())", list);
        p("list.stream().mapToInt(Integer::intValue).toArray()", list.stream().mapToInt(Integer::intValue).toArray());
        p("Arrays.stream(a).boxed().collect(toList())", Arrays.stream(a).boxed().collect(Collectors.toList()));

        System.out.println("== 3 Map / Set ==");
        Map<String, Integer> cnt = new HashMap<>();
        for (String w : "buy sell buy buy".split(" ")) cnt.merge(w, 1, Integer::sum);
        p("cnt.merge(w, 1, Integer::sum)  // 计数", new TreeMap<>(cnt));
        p("cnt.getOrDefault(\"cancel\", 0)", cnt.getOrDefault("cancel", 0));
        Map<Character, List<String>> group = new TreeMap<>();
        for (String w : new String[]{"apple", "avocado", "banana"}) group.computeIfAbsent(w.charAt(0), k -> new ArrayList<>()).add(w);
        p("computeIfAbsent(k, k -> new ArrayList<>()).add(w)", group);
        Map<String, Integer> m = new HashMap<>();
        p("m.put(\"a\", 1)   // 返回旧值", m.put("a", 1));
        p("m.put(\"a\", 2)", m.put("a", 2));
        p("m.putIfAbsent(\"a\", 3)  // 已有就不放，返回旧值", m.putIfAbsent("a", 3));
        Set<Integer> seen = new HashSet<>();
        p("seen.add(7) 第一次 / 第二次", seen.add(7) + " / " + seen.add(7));
        TreeMap<Integer, String> tm = new TreeMap<>();
        tm.put(100, "a"); tm.put(103, "b"); tm.put(108, "c");
        p("TreeMap{100,103,108}.floorKey(105)   // ≤", tm.floorKey(105));
        p("tm.ceilingKey(105)   // ≥", tm.ceilingKey(105));
        p("tm.higherKey(103) / lowerKey(103)  // > / <", tm.higherKey(103) + " / " + tm.lowerKey(103));
        p("tm.ceilingKey(200)   // 没有", tm.ceilingKey(200));
        p("tm.firstKey() / lastKey()", tm.firstKey() + " / " + tm.lastKey());
        p("tm.headMap(103)   // < 103", tm.headMap(103));
        p("tm.subMap(100, 108)   // [100,108)", tm.subMap(100, 108));
        p("tm.pollFirstEntry()", tm.pollFirstEntry());

        System.out.println("== 4 栈、队列、堆 ==");
        Deque<Integer> st = new ArrayDeque<>();
        st.push(1); st.push(2); st.push(3);
        p("push 1 2 3 后 pop() / peek()", st.pop() + " / " + st.peek());
        Deque<Integer> q = new ArrayDeque<>();
        q.offer(1); q.offer(2); q.offer(3);
        p("offer 1 2 3 后 poll() / peek()", q.poll() + " / " + q.peek());
        p("空 ArrayDeque 的 poll() / peek()", new ArrayDeque<Integer>().poll() + " / " + new ArrayDeque<Integer>().peek());
        try { new ArrayDeque<Integer>().pop(); }
        catch (NoSuchElementException e) { p("空 ArrayDeque 的 pop() / remove()", "NoSuchElementException"); }
        PriorityQueue<Integer> minQ = new PriorityQueue<>(Arrays.asList(5, 1, 8));
        PriorityQueue<Integer> maxQ = new PriorityQueue<>(Collections.reverseOrder());
        maxQ.addAll(Arrays.asList(5, 1, 8));
        p("new PriorityQueue<>()  peek()  // 小顶", minQ.peek());
        p("new PriorityQueue<>(Collections.reverseOrder()) peek()", maxQ.peek());
        PriorityQueue<int[]> byCol = new PriorityQueue<>((x, y) -> Integer.compare(x[1], y[1]));
        byCol.offer(new int[]{1, 30}); byCol.offer(new int[]{2, 10});
        p("PriorityQueue<int[]> 按 [1] 排 peek()", byCol.peek());

        System.out.println("== 5 比较器 Comparator ==");
        List<String> ws = new ArrayList<>(Arrays.asList("bb", "a", "ccc", "ab"));
        ws.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        p("comparingInt(String::length).thenComparing(naturalOrder())", ws);
        ws.sort(Comparator.comparingInt(String::length).reversed());
        p("comparingInt(String::length).reversed()", ws);
        ws.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.<String>naturalOrder()).reversed());
        p("...thenComparing(naturalOrder()).reversed() // 坑：整条都反", ws);
        p("Integer.compare(3, 7) / compare(7, 3)", Integer.compare(3, 7) + " / " + Integer.compare(7, 3));

        System.out.println("== 6 数学和整数 ==");
        p("Math.max(3, 7) / Math.min(3, 7)", Math.max(3, 7) + " / " + Math.min(3, 7));
        p("Math.abs(-5)", Math.abs(-5));
        p("Math.abs(Integer.MIN_VALUE)   // 坑", Math.abs(Integer.MIN_VALUE));
        p("Integer.MAX_VALUE + 1   // 坑：溢出", Integer.MAX_VALUE + 1);
        p("(long) Integer.MAX_VALUE + 1", (long) Integer.MAX_VALUE + 1);
        p("Math.pow(2, 10)   // 返回 double", Math.pow(2, 10));
        p("(int) Math.sqrt(17)", (int) Math.sqrt(17));
        p("7 / 2 和 -7 / 2   // 向 0 取整", (7 / 2) + " 和 " + (-7 / 2));
        p("-7 % 3 和 Math.floorMod(-7, 3)", (-7 % 3) + " 和 " + Math.floorMod(-7, 3));
        int lo = 2_000_000_000, hi = 2_100_000_000;
        p("(lo + hi) / 2   // lo=2e9, hi=2.1e9 坑", (lo + hi) / 2);
        p("lo + (hi - lo) / 2", lo + (hi - lo) / 2);
        p("(lo + hi) >>> 1", (lo + hi) >>> 1);
        p("Integer.parseInt(\"-42\") / Long.parseLong(\"9000000000\")", Integer.parseInt("-42") + " / " + Long.parseLong("9000000000"));
        p("Integer.toBinaryString(10) / Integer.bitCount(10)", Integer.toBinaryString(10) + " / " + Integer.bitCount(10));
        p("Integer.toString(255, 16) / parseInt(\"ff\", 16)", Integer.toString(255, 16) + " / " + Integer.parseInt("ff", 16));
        Integer x1 = 127, y1 = 127, x2 = 128, y2 = 128;
        p("Integer 127 == 127 / 128 == 128   // 坑", (x1 == y1) + " / " + (x2 == y2));
        p("Integer 128 .equals(128)", x2.equals(y2));

        System.out.println("== 7 字符串和字符 ==");
        String str = "BTC-USDT";
        p("\"BTC-USDT\".charAt(0) / length()", str.charAt(0) + " / " + str.length());
        p("str.substring(0, 3)   // [0,3)", str.substring(0, 3));
        p("str.substring(4)", str.substring(4));
        p("str.indexOf('-') / indexOf(\"X\")", str.indexOf('-') + " / " + str.indexOf("X"));
        p("\"a,b,,c,,\".split(\",\")   // 坑：尾部空串被丢", "a,b,,c,,".split(","));
        p("\"1.5.2\".split(\".\").length   // 坑：. 是正则", "1.5.2".split(".").length);
        p("\"1.5.2\".split(\"\\\\.\")", "1.5.2".split("\\."));
        p("\"  a b  \".trim()", "[" + "  a b  ".trim() + "]");
        p("String.join(\"-\", \"a\", \"b\", \"c\")", String.join("-", "a", "b", "c"));
        char[] cs = "dcba".toCharArray(); Arrays.sort(cs);
        p("toCharArray() 排序后 new String(cs)", new String(cs));
        StringBuilder sb = new StringBuilder("abc");
        sb.append('d').reverse();
        p("new StringBuilder(\"abc\").append('d').reverse()", sb);
        sb.deleteCharAt(sb.length() - 1); sb.setCharAt(0, 'X'); sb.insert(1, "_");
        p("deleteCharAt(末尾) setCharAt(0,'X') insert(1,\"_\")", sb);
        p("'7' - '0' / 'c' - 'a'", ('7' - '0') + " / " + ('c' - 'a'));
        p("(char) ('a' + 2)", (char) ('a' + 2));
        p("Character.isDigit('7') / isLetter('x')", Character.isDigit('7') + " / " + Character.isLetter('x'));
        p("Character.toUpperCase('q')", Character.toUpperCase('q'));
        String s1 = "ab", s2 = new String("ab");
        p("\"ab\" == new String(\"ab\")   // 坑", s1 == s2);
        p("\"ab\".equals(new String(\"ab\"))", s1.equals(s2));
        p("\"apple\".compareTo(\"banana\")", "apple".compareTo("banana"));
    }
}
