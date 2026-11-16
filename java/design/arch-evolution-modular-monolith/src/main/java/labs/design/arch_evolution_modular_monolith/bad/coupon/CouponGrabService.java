package labs.design.arch_evolution_modular_monolith.bad.coupon;

import labs.design.arch_evolution_modular_monolith.bad.pricing.internal.RuleChain;
import org.springframework.stereotype.Service;

/**
 * 抢券。有人想在抢到券以后顺手提示“用了能省多少”，图省事直接 new 了算价模块的内部类。
 * 两个问题：伸手进了 pricing 的 internal；而且 pricing 本来依赖 coupon，这下 coupon 又依赖 pricing，成了环。
 */
@Service
public class CouponGrabService {
    public String grab(long userId) {
        long save = 400_00 - new RuleChain().apply(400_00, 90);
        return "C1（现在用能省 " + save / 100 + " 元）";
    }
}
