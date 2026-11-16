package labs.design.arch_evolution_modular_monolith;

import labs.design.arch_evolution_modular_monolith.bad.BadApp;
import labs.design.arch_evolution_modular_monolith.good.GoodApp;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 让 Spring Modulith 检查两份代码的模块边界。平时这段写在测试里，CI 每次都跑。
 * 运行：mvn -q -pl design/arch-evolution-modular-monolith compile exec:java
 *       -Dexec.mainClass=labs.design.arch_evolution_modular_monolith.VerifyDemo
 */
public class VerifyDemo {
    public static void main(String[] args) {
        check("good：模块只走对方的公开接口", GoodApp.class);
        check("bad：抢券伸手进了算价的 internal", BadApp.class);
    }

    static void check(String title, Class<?> app) {
        System.out.println("== " + title);
        ApplicationModules modules = ApplicationModules.of(app);
        modules.forEach(m -> System.out.println("模块 " + m.getName() + " ← 包 " + m.getBasePackage().getName()));
        try {
            modules.verify();
            System.out.println("verify() 通过\n");
        } catch (RuntimeException e) {
            System.out.println("verify() 失败：" + e.getClass().getSimpleName());
            System.out.println(e.getMessage() + "\n");
        }
    }
}
