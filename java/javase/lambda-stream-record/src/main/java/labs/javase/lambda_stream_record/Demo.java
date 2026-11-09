package labs.javase.lambda_stream_record;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * 全景第 2、5、6 站用到的 Lambda、Stream、record，在 JDK 17 上看它们编译后、运行时是什么。
 * java -cp target/classes labs.javase.lambda_stream_record.Demo
 * 字节码：javap -c -p target/classes/labs/javase/lambda_stream_record/Demo.class
 */
public class Demo {

    record OrderLine(long skuId, String name, BigDecimal price, int qty) {
        OrderLine {                                            // 紧凑构造器：校验，字段赋值由编译器补
            if (qty <= 0) throw new IllegalArgumentException("qty 必须大于 0：" + qty);
            Objects.requireNonNull(price, "price");
        }
        BigDecimal amount() { return price.multiply(BigDecimal.valueOf(qty)); }
    }

    record Cart(long userId, List<OrderLine> lines) {}           // 浅不可变：List 本身可能是可变的
    record SafeCart(long userId, List<OrderLine> lines) {
        SafeCart { lines = List.copyOf(lines); }
    }

    static Supplier<String> nonCapturing() { return () -> "无捕获"; }
    static Supplier<String> capturing(String user) { return () -> "捕获了 " + user; }

    static OrderLine line(long id, String price, int qty) { return new OrderLine(id, "商品" + id, new BigDecimal(price), qty); }

    public static void main(String[] args) {
        System.out.println(System.getProperty("java.version"));

        System.out.println("\n== 1. Lambda 不是匿名内部类 ==");
        long t0 = System.nanoTime();
        Supplier<String> first = () -> "第一次";
        long firstNs = System.nanoTime() - t0;
        t0 = System.nanoTime();
        Supplier<String> second = () -> "第二个";
        long secondNs = System.nanoTime() - t0;
        Runnable anon = new Runnable() { public void run() {} };
        System.out.println("  lambda 的类：" + first.getClass().getName() + "，isHidden=" + first.getClass().isHidden());
        System.out.println("  匿名内部类：" + anon.getClass().getName() + "，isHidden=" + anon.getClass().isHidden());
        System.out.printf("  这一处 lambda 第一次求值 %.2f ms（引导、生成类），下一处 %.3f ms%n", firstNs / 1e6, secondNs / 1e6);
        System.out.println("  无捕获的 lambda 两次拿到同一个对象吗？" + (nonCapturing() == nonCapturing()));
        System.out.println("  捕获了变量的 lambda 两次拿到同一个对象吗？" + (capturing("1024") == capturing("1024")));

        System.out.println("\n== 2. Stream 是惰性的，一个元素走完整条流水线再轮到下一个 ==");
        List<OrderLine> lines = List.of(line(66, "19.90", 1), line(88, "299.00", 2), line(99, "129.00", 1), line(12, "9.90", 3), line(7, "599.00", 1));
        Stream<OrderLine> s = lines.stream()
                .peek(l -> System.out.println("    filter 看到 " + l.skuId()))
                .filter(l -> l.price().compareTo(new BigDecimal("100")) > 0)
                .peek(l -> System.out.println("    map    看到 " + l.skuId()));
        System.out.println("  流水线搭好了，还没有终端操作：上面一行都没打印");
        Optional<Long> firstExpensive = s.map(OrderLine::skuId).findFirst();
        System.out.println("  findFirst → " + firstExpensive.orElse(-1L) + "（66 被 filter 拦下，88 一路走到底，找到就停，99、12、7 都没被看）");
        try {
            s.count();
        } catch (IllegalStateException e) {
            System.out.println("  同一个 Stream 再用一次 → IllegalStateException: " + e.getMessage());
        }
        System.out.println("  加一个 sorted（按单价从高到低）再 findFirst：");
        Optional<Long> top = lines.stream()
                .peek(l -> System.out.println("    filter 看到 " + l.skuId()))
                .filter(l -> l.price().compareTo(new BigDecimal("100")) > 0)
                .sorted(Comparator.comparing(OrderLine::price).reversed())
                .peek(l -> System.out.println("    map    看到 " + l.skuId()))
                .map(OrderLine::skuId).findFirst();
        System.out.println("  → " + top.orElse(-1L) + "（sorted 要先收齐所有元素才能排，5 个全被 filter 看过）");
        Map<Boolean, List<Long>> split = lines.stream().collect(Collectors.partitioningBy(
                l -> l.price().compareTo(new BigDecimal("100")) > 0, Collectors.mapping(OrderLine::skuId, Collectors.toList())));
        System.out.println("  partitioningBy 单价 > 100：" + split);

        System.out.println("\n== 3. parallelStream 用的是全局公共线程池 ==");
        Set<String> threads = Collections.synchronizedSet(new TreeSet<>());
        LongStream.range(0, 200_000).parallel().forEach(i -> threads.add(Thread.currentThread().getName()));
        System.out.println("  CPU 核数 " + Runtime.getRuntime().availableProcessors() + "，干活的线程：" + threads);

        System.out.println("\n== 4. 装箱的代价：1000 万个数求和（各跑 5 次取最快） ==");
        int n = 10_000_000;
        System.out.printf("  for 循环 long         %7.1f ms%n", best(() -> { long t = 0; for (long i = 0; i < n; i++) t += i; return t; }));
        System.out.printf("  LongStream.sum        %7.1f ms%n", best(() -> LongStream.range(0, n).sum()));
        System.out.printf("  Stream<Long>.reduce   %7.1f ms%n", best(() -> Stream.iterate(0L, i -> i + 1).limit(n).reduce(0L, Long::sum)));

        System.out.println("\n== 5. record：编译器生成了什么 ==");
        OrderLine a = line(88, "299.00", 2), b = line(88, "299.00", 2);
        System.out.println("  toString：" + a);
        System.out.println("  a.equals(b)=" + a.equals(b) + "，hashCode 相等=" + (a.hashCode() == b.hashCode()) + "，a == b：" + (a == b));
        System.out.println("  isRecord=" + OrderLine.class.isRecord() + "，组件：" + Arrays.stream(OrderLine.class.getRecordComponents())
                .map(RecordComponent::getName).toList() + "（Jackson 就靠这些组件名对上 JSON 的键）");
        try { line(88, "299.00", 0); } catch (IllegalArgumentException e) { System.out.println("  紧凑构造器校验：" + e.getMessage()); }
        List<OrderLine> mutable = new ArrayList<>(lines.subList(0, 2));
        Cart cart = new Cart(1024, mutable);
        SafeCart safe = new SafeCart(1024, mutable);
        mutable.add(line(7, "599.00", 1));
        System.out.println("  外面往原列表加了一行：Cart 里变成 " + cart.lines().size() + " 行，SafeCart 还是 " + safe.lines().size() + " 行");
    }

    static double best(Supplier<Long> task) {
        double min = Double.MAX_VALUE; long sink = 0;
        for (int i = 0; i < 5; i++) { long t = System.nanoTime(); sink += task.get(); min = Math.min(min, (System.nanoTime() - t) / 1e6); }
        if (sink == 42) System.out.println();
        return min;
    }
}
