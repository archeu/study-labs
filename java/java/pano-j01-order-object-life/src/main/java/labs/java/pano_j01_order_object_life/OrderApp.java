package labs.java.pano_j01_order_object_life;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 全景 J01「一个订单对象的一生」的实测程序。
 * 启动后 POST /orders，日志里按站打印：每一站看到的类名、哈希桶下标、耗时。
 * 运行：见 README。
 */
@SpringBootApplication
public class OrderApp {
    public static void main(String[] args) {
        SpringApplication.run(OrderApp.class, args);
    }
}
