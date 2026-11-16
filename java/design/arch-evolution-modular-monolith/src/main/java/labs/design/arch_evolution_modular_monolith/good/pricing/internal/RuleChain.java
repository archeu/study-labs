package labs.design.arch_evolution_modular_monolith.good.pricing.internal;

/** pricing 模块的内部实现：在子包 internal 里，别的模块不该碰。 */
public class RuleChain {
    public long apply(long total, int percent) {
        long pay = total >= 300_00 ? total - 40_00 : total;     // 先满减
        return (pay * percent + 50) / 100;                       // 后用券
    }
}
