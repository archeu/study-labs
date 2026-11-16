package labs.design.arch_evolution_modular_monolith.good.pricing;

import labs.design.arch_evolution_modular_monolith.good.coupon.CouponQuery;
import labs.design.arch_evolution_modular_monolith.good.pricing.internal.RuleChain;
import org.springframework.stereotype.Service;

/** pricing 模块的公开接口。它依赖 coupon 模块的公开接口 CouponQuery：pricing → coupon，方向是允许的。 */
@Service
public class PricingFacade {
    private final CouponQuery coupons;
    private final RuleChain rules = new RuleChain();

    public PricingFacade(CouponQuery coupons) { this.coupons = coupons; }

    public long quote(long total, String couponId) {
        return rules.apply(total, coupons.percentOf(couponId));
    }
}
