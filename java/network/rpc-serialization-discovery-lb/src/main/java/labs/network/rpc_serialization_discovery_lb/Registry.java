package labs.network.rpc_serialization_discovery_lb;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 服务发现：一个内存版注册中心，代替 ZooKeeper / Nacos。
 * 提供者上线 register、下线 unregister；调用方 subscribe 拿到当前列表，之后有变化就被推送。
 */
public final class Registry {
    private final Map<String, Set<String>> services = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<List<String>>>> listeners = new ConcurrentHashMap<>();

    public void register(String service, String address) {
        services.computeIfAbsent(service, k -> new ConcurrentSkipListSet<>()).add(address);
        notifyListeners(service);
    }

    public void unregister(String service, String address) {
        Set<String> s = services.get(service);
        if (s != null && s.remove(address)) notifyListeners(service);
    }

    public void subscribe(String service, Consumer<List<String>> listener) {
        listeners.computeIfAbsent(service, k -> new CopyOnWriteArrayList<>()).add(listener);
        listener.accept(lookup(service));
    }

    public List<String> lookup(String service) {
        return new ArrayList<>(services.getOrDefault(service, Collections.<String>emptySet()));
    }

    private void notifyListeners(String service) {
        List<String> now = lookup(service);
        for (Consumer<List<String>> l : listeners.getOrDefault(service, Collections.<Consumer<List<String>>>emptyList())) {
            l.accept(now);
        }
    }
}
