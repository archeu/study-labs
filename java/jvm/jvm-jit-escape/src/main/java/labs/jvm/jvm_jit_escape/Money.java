package labs.jvm.jvm_jit_escape;

/** 交易靶场里的金额：不可变，每次运算都 new 一个新的。对象头 12 字节 + long 8 字节，对齐后 24 字节。 */
final class Money {
    final long cents;
    Money(long cents) { this.cents = cents; }
    Money times(int qty) { return new Money(cents * qty); }
    Money off(int pct) { return new Money(cents * (100 - pct) / 100); }
}
