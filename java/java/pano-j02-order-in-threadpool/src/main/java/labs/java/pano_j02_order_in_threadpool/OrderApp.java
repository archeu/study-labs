package labs.java.pano_j02_order_in_threadpool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 交易靶场 J02：20:00 秒杀，88 号机械键盘只有 100 件，1000 个用户同时点“下单”。
 * 不带参数：起服务，自己用 curl 打。
 * 带 --rush：起服务后自己当压测客户端，五种扣库存写法各跑一轮，打印结果后退出。
 */
@SpringBootApplication
public class OrderApp {
    public static void main(String[] args) {
        SpringApplication.run(OrderApp.class, args);
    }
}
