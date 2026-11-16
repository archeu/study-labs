package labs.design.design_patterns_in_frameworks;

/** 算价对外的样子。调用方只认这个接口，背后是链、是代理，它不知道。 */
public interface Pricing {
    Quote quote(Cart cart, Offer offer);
}
