package labs.design.arch_evolution_modular_monolith.good.coupon;

import org.springframework.stereotype.Service;

/** coupon 模块的公开接口：放在模块的根包里，别的模块可以用。 */
@Service
public class CouponQuery {
    public int percentOf(String couponId) { return "C1".equals(couponId) ? 90 : 100; }
}
