package labs.design.design_patterns_in_frameworks;

import java.util.ArrayList;
import java.util.List;

/** 一次算价的中间结果：现在该付多少，以及一路上谁减了多少。不可变，每条规则返回一个新的。 */
public record Quote(long payable, List<String> trace) {
    public static Quote of(long total) { return new Quote(total, List.of()); }

    public Quote minus(String rule, long amount) {
        List<String> t = new ArrayList<>(trace);
        t.add(rule + " -" + PatternsWalkthrough.yuan(amount));
        return new Quote(payable - amount, List.copyOf(t));
    }
}
