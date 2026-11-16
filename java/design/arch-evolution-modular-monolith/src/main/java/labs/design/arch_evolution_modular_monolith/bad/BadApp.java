package labs.design.arch_evolution_modular_monolith.bad;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 券服务 coupon-svc 的启动类。它所在的包往下一层，每个子包是一个模块：coupon、pricing。 */
@SpringBootApplication
public class BadApp {}
