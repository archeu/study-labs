package labs.design.arch_evolution_modular_monolith.good.coupon;

import org.springframework.stereotype.Service;

/** 抢券。只用自己模块里的东西。 */
@Service
public class CouponGrabService {
    public String grab(long userId) { return "C1"; }
}
