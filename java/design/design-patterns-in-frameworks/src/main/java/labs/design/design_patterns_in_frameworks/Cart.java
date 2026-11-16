package labs.design.design_patterns_in_frameworks;

/** 购物车：每一行的金额，单位“分”。 */
public record Cart(long... prices) {
    public long total() { long t = 0; for (long p : prices) t += p; return t; }
}
