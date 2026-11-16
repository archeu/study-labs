package labs.design.refactoring_tech_debt;

/** 搬出来以后的样子：一种券一个实现。 */
public interface Rule {
    boolean matches(int type);
    long pay(long total, long value);
}
