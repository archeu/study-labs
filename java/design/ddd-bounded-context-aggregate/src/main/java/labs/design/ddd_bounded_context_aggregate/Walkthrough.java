package labs.design.ddd_bounded_context_aggregate;

import labs.design.ddd_bounded_context_aggregate.app.PromotionEventHandler;
import labs.design.ddd_bounded_context_aggregate.app.PromotionPricingAdapter;
import labs.design.ddd_bounded_context_aggregate.ordering.Order;
import labs.design.ddd_bounded_context_aggregate.ordering.OrderEvents.OrderPaid;
import labs.design.ddd_bounded_context_aggregate.ordering.PriceSnapshot;
import labs.design.ddd_bounded_context_aggregate.promotion.Coupon;
import labs.design.ddd_bounded_context_aggregate.promotion.PricingService;
import labs.design.ddd_bounded_context_aggregate.promotion.Promotion;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 用户 1024 那一单，在“订单管事实、营销管规则”的两个上下文里走一遍。
 * 运行：mvn -q -pl design/ddd-bounded-context-aggregate compile exec:java
 *       -Dexec.mainClass=labs.design.ddd_bounded_context_aggregate.Walkthrough
 */
public class Walkthrough {
    static final long[] CART = {199_00, 129_00, 72_00};     // A 耳机、B 保温杯、C 数据线

    public static void main(String[] args) {
        // 预算故意设成 100 元，好看到“花超了”那一刻
        Promotion p1 = new Promotion("P1", 300_00, 40_00, 100_00);
        Coupon c1 = new Coupon("C1", 90);
        PromotionEventHandler marketing = new PromotionEventHandler(Map.of("P1", p1), Map.of("C1", c1));

        System.out.println("== 1. 下单：订单问营销怎么算，把答案存成自己的快照");
        Order o1 = Order.place("O1", CART, new PromotionPricingAdapter(p1, c1));
        System.out.println("应付 " + yuan(o1.payable()) + "，每行分摊 " + yuans(o1.snapshot().lineDiscounts())
            + "，券 " + c1.status());

        System.out.println("\n== 2. 支付：订单发出 OrderPaid，营销听到后在自己的事务里记账");
        OrderPaid paid = o1.pay();
        System.out.println("事件 " + paid);
        System.out.println("营销处理：" + marketing.on(paid) + " → 券 " + c1.status() + "，预算已用 " + yuan(p1.used()) + " / " + yuan(p1.budget()));
        System.out.println("同一个事件又投了一次：" + marketing.on(paid) + " → 预算已用 " + yuan(p1.used()));

        System.out.println("\n== 3. 运营把活动改成满 400 减 50，然后用户退保温杯");
        p1.change(400_00, 50_00);
        long byRecalc = CART[1] - new PricingService().quote(CART, p1, c1).lineDiscounts()[1];
        System.out.println("订单按快照退：" + yuan(o1.refundLine(1).refunded()));
        System.out.println("对照，这时找营销重算：" + yuan(byRecalc));

        System.out.println("\n== 4. 聚合守着的不变量");
        attempt("保温杯再退一次", () -> o1.refundLine(1));
        attempt("没付款的订单退货", () -> Order.place("O9", CART, (id, prices) -> new PriceSnapshot(new long[3], null, 0, null)).refundLine(0));
        attempt("快照里一行的分摊比行原价还多", () -> Order.placeWith("O8", CART, new PriceSnapshot(new long[]{0, 200_00, 0}, "P1", 40_00, null)));
        attempt("另一张订单想用同一张券", () -> Order.place("O7", CART, new PromotionPricingAdapter(p1, c1)));

        System.out.println("\n== 5. 预算在订单事务之外记账：会花超一点");
        for (String id : new String[]{"O2", "O3"}) {
            Order o = Order.place(id, CART, new PromotionPricingAdapter(p1, null));
            long before = p1.budget() - p1.used();
            marketing.on(o.pay());
            System.out.println(id + " 应付 " + yuan(o.payable()) + "；付款前预算剩 " + yuan(before) + "，付款后已用 "
                + yuan(p1.used()) + " / " + yuan(p1.budget()) + (p1.ended() ? "，超 " + yuan(p1.used() - p1.budget()) + "，活动结束" : ""));
        }
        Order o4 = Order.place("O4", CART, new PromotionPricingAdapter(p1, null));
        System.out.println("O4 应付 " + yuan(o4.payable()) + "（活动已结束，不再减）");
    }

    static void attempt(String what, Supplier<?> action) {
        try {
            action.get();
            System.out.println(what + " → 居然成功了");
        } catch (RuntimeException e) {
            System.out.println(what + " → 拒绝：" + e.getMessage());
        }
    }

    static String yuan(long cents) { return String.format("%d.%02d", cents / 100, cents % 100); }
    static String yuans(long[] cents) { return String.join(" / ", Arrays.stream(cents).mapToObj(Walkthrough::yuan).toArray(String[]::new)); }
}
