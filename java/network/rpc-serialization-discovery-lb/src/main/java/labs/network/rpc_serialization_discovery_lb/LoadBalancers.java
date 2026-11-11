package labs.network.rpc_serialization_discovery_lb;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** 负载均衡：从“现在活着的地址”里挑一个。active 是每个地址上还没回来的请求数。 */
public final class LoadBalancers {
    private LoadBalancers() {}

    public interface LoadBalancer {
        String pick(List<String> addresses, Map<String, AtomicInteger> active);
    }

    public static LoadBalancer roundRobin() {
        final AtomicInteger next = new AtomicInteger();
        return (addrs, active) -> addrs.get(Math.floorMod(next.getAndIncrement(), addrs.size()));
    }

    public static LoadBalancer random() {
        return (addrs, active) -> addrs.get(ThreadLocalRandom.current().nextInt(addrs.size()));
    }

    /** 最少活跃：挑在途请求最少的那台；一样少就随机。慢的实例在途请求会积压，自然少分。 */
    public static LoadBalancer leastActive() {
        return (addrs, active) -> {
            String best = null; int min = Integer.MAX_VALUE, ties = 0;
            for (String a : addrs) {
                int n = active.containsKey(a) ? active.get(a).get() : 0;
                if (n < min) { min = n; best = a; ties = 1; }
                else if (n == min && ThreadLocalRandom.current().nextInt(++ties) == 0) best = a;
            }
            return best;
        };
    }
}
