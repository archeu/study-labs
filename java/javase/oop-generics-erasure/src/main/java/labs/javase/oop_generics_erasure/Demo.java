package labs.javase.oop_generics_erasure;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 交易靶场第 5、6 站的最小案例：new Order、算价、看泛型擦除。
 * JDK 17：javac --release 17。运行见 README。
 */
public class Demo {

    /** 按订单号排序的比较器：编译器会为它生成一个桥方法 compare(Object, Object)。 */
    static class ById implements Comparator<Order> {
        @Override
        public int compare(Order a, Order b) { return Long.compare(a.id(), b.id()); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. 封装：new Order，状态只能按规则改 ==");
        List<OrderLine> input = new ArrayList<>();
        input.add(new OrderLine(88, "机械键盘", new BigDecimal("299.00"), 2));
        Order order = new Order(9527, 1024, input);
        input.clear();                                     // 外面清空原列表
        System.out.println("  外面清空了原列表，订单里还有 " + order.lines().size() + " 行，合计 " + order.sum());
        try {
            order.lines().add(input.isEmpty() ? new OrderLine(1, "x", BigDecimal.ONE, 1) : null);
        } catch (UnsupportedOperationException e) {
            System.out.println("  order.lines().add(...) → UnsupportedOperationException：订单行对外只读");
        }
        order.pay();
        try {
            order.cancel();
        } catch (IllegalStateException e) {
            System.out.println("  付款后 cancel() → " + e.getMessage());
        }

        System.out.println("\n== 2. 多态：同一行 d.apply(sum)，跑的是各自的实现 ==");
        BigDecimal sum = order.sum();
        List<Discount> discounts = List.of(
                new Discount.None(),
                new Discount.Minus(new BigDecimal("20")),
                new Discount.Percent(10),
                new FullReduction(new BigDecimal("300"), new BigDecimal("30")));
        for (Discount d : discounts) {
            System.out.printf("  %-14s 运行时类 %-28s apply(%s) = %s%n",
                    d.label(), d.getClass().getName().replace("labs.javase.oop_generics_erasure.", ""), sum, d.apply(sum));
        }

        System.out.println("\n== 3. 擦除：运行时 List<OrderLine> 和 List<String> 是同一个类 ==");
        List<OrderLine> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        System.out.println("  a.getClass() == b.getClass()：" + (a.getClass() == b.getClass()) + "（都是 " + a.getClass().getName() + "）");
        System.out.println("  但 Order.lines 字段的声明还在 class 文件里：" + Order.class.getDeclaredField("lines").getGenericType()
                .getTypeName().replace("labs.javase.oop_generics_erasure.", ""));
        System.out.println("  它的运行时类型只是：" + Order.class.getDeclaredField("lines").getType().getName());

        System.out.println("\n== 4. 原始类型绕过检查：错在放进去的地方，炸在取出来的地方 ==");
        List<OrderLine> lines = new ArrayList<>(order.lines());
        addSomething(lines);                                // 第 1 处：放进去，不报错
        System.out.println("  addSomething 之后 lines.size() = " + lines.size() + "，没有报错");
        try {
            for (OrderLine l : lines) {                     // 第 2 处：取出来时编译器插的 checkcast 才报错
                System.out.println("  读到 " + l.name());
            }
        } catch (ClassCastException e) {
            StackTraceElement top = e.getStackTrace()[0];
            System.out.println("  ClassCastException 在 " + top.getMethodName() + "():" + top.getLineNumber() + " 抛出，不在 addSomething 里");
            System.out.println("  " + e.getMessage().replace("labs.javase.oop_generics_erasure.", ""));
        }

        System.out.println("\n== 5. 桥方法：擦除后为了让多态还成立，编译器补的方法 ==");
        Arrays.stream(ById.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("compare"))
                .sorted(Comparator.comparing(Method::isBridge))
                .forEach(m -> System.out.println("  " + (m.isBridge() ? "bridge   " : "你写的   ") + m.toGenericString()
                        .replace("labs.javase.oop_generics_erasure.", "")));

        System.out.println("\n== 6. 坑：父类构造器调了子类覆盖的方法 ==");
        System.out.println("  上面 new FullReduction(300, 30) 时打印的是“满 300 减 null”：父类构造器跑的时候，子类字段 off 还没赋值");
    }

    /** 拿原始类型 List 接参数：编译只给 unchecked 警告。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void addSomething(List raw) {
        raw.add("一行字符串，不是 OrderLine");
    }
}
