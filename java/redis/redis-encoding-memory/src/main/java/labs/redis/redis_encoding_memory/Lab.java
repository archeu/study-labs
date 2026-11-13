package labs.redis.redis_encoding_memory;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 全景 J07 第 7 站：88 号详情回填进 Redis，到底占多少内存、存成什么编码。
 * 三段：one（88 号一条，拆开 805 字节）、embstr（key 越长，embstr 越短）、shapes（同一份详情四种存法，批量算每个商品的真实开销）。
 * 用法：java -Dport=63798 Lab [one|embstr|shapes]，不带参数三段都跑。
 */
public class Lab {
    /** -DsameDetail=true：所有商品用同一段详情，看 RedisJSON 对相同字符串只存一份 */
    static boolean sameDetail;
    /** 和全景页 ProductDetail 建的数据一样：一句 32 个汉字的话重复 6 遍，576 字节 */
    static final String DETAIL = repeat("这是靶场里的示例商品，详情页文字包括规格、材质、产地、售后说明。", 6);

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("host", "127.0.0.1");
        int port = Integer.getInteger("port", 63798);
        RedisClient client = RedisClient.create("redis://" + host + ":" + port);
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> r = conn.sync();
            String ver = info(r, "server", "redis_version");
            System.out.println("== Redis " + ver + "，" + info(r, "memory", "mem_allocator") + " ==");
            String part = args.length > 0 ? args[0] : "all";
            if (part.equals("all") || part.equals("one")) one(r);
            if (part.equals("all") || part.equals("embstr")) embstr(r);
            if (part.equals("all") || part.equals("shapes")) shapes(r, conn.async(), ver.startsWith("8"));
        } finally {
            client.shutdown();
        }
    }

    /** 第一段：88 号回填一次，看编码和 MEMORY USAGE */
    static void one(RedisCommands<String, String> r) {
        r.flushall();
        String json = json(88, DETAIL);
        System.out.println("\n[one] 88 号 JSON " + bytes(json) + " 字节（detail " + bytes(DETAIL) + " 字节）");
        r.set("product:88", json);
        System.out.printf("  SET 不带 TTL     : %-6s MEMORY USAGE %d%n", r.objectEncoding("product:88"), r.memoryUsage("product:88"));
        r.set("product:88", json, SetArgs.Builder.ex(1843));
        System.out.printf("  SET ... EX 1843 : %-6s MEMORY USAGE %d   STRLEN %d%n",
                r.objectEncoding("product:88"), r.memoryUsage("product:88"), r.strlen("product:88"));
        r.set("stock:88", "1200");
        System.out.printf("  SET stock:88 1200: %-6s MEMORY USAGE %d%n", r.objectEncoding("stock:88"), r.memoryUsage("stock:88"));
        r.set("price:88", "399.00");
        System.out.printf("  SET price:88 399.00: %-6s MEMORY USAGE %d%n", r.objectEncoding("price:88"), r.memoryUsage("price:88"));
    }

    /** 第二段：key 长度不同，值最长多少字节还是 embstr */
    static void embstr(RedisCommands<String, String> r) {
        r.flushall();
        System.out.println("\n[embstr] key 长度 → 值最长多少字节仍是 embstr");
        for (int kl : new int[]{3, 10, 24, 40}) {
            String key = repeat("k", kl);
            int max = -1;
            for (int vl = 1; vl <= 50; vl++) {
                r.set(key, repeat("v", vl));
                if ("embstr".equals(r.objectEncoding(key))) max = vl;
            }
            r.del(key);
            System.out.printf("  key %2d 字节 → 值 ≤ %2d 字节%n", kl, max);
        }
    }

    /** 第三段：同一份详情四种存法，各存 N 个商品，看 MEMORY USAGE 和 used_memory 涨了多少 */
    static void shapes(RedisCommands<String, String> r, RedisAsyncCommands<String, String> a, boolean hasJson) throws Exception {
        int n = Integer.getInteger("n", 50000);
        sameDetail = Boolean.getBoolean("sameDetail");
        System.out.println("\n[shapes] 每种存 " + n + " 个商品，TTL 1800 s，详情" + (sameDetail ? "全都一样" : "各不相同") + "；每个商品的平均开销 = used_memory 增量 / " + n);
        System.out.println("  hash-max-listpack-value = " + r.configGet("hash-max-listpack-value").values());
        List<String> kinds = new ArrayList<>();
        kinds.add("string");          // product:{id} = 648 字节 JSON
        kinds.add("hash");            // product:h:{id} 五个字段，detail 558 字节
        kinds.add("hash-split");      // 四个短字段一个 hash + detail 单独一个 string
        if (hasJson) kinds.add("json"); // JSON.SET，Redis 8 自带
        for (String kind : kinds) {
            r.flushall();
            Thread.sleep(300);
            long before = Long.parseLong(info(r, "memory", "used_memory"));
            List<RedisFuture<?>> fs = new ArrayList<>();
            for (int id = 1; id <= n; id++) {
                write(a, kind, id, fs);
                if (fs.size() >= 2000) { for (RedisFuture<?> f : fs) f.get(5, TimeUnit.SECONDS); fs.clear(); }
            }
            for (RedisFuture<?> f : fs) f.get(5, TimeUnit.SECONDS);
            long after = Long.parseLong(info(r, "memory", "used_memory"));
            String k88 = kind.startsWith("hash") ? "product:h:88" : "product:88";
            long mu = r.memoryUsage(k88);
            if (kind.equals("hash-split")) mu += r.memoryUsage("detail:88");
            System.out.printf("  %-10s 编码 %-9s MEMORY USAGE(88 号) %5d   每个商品实际 %5d 字节%n",
                    kind, r.objectEncoding(k88), mu, (after - before) / n);
        }
        r.flushall();
    }

    static void write(RedisAsyncCommands<String, String> a, String kind, int id, List<RedisFuture<?>> fs) {
        String detail = sameDetail ? DETAIL : detail(id);
        String json = json(id, detail);
        switch (kind) {
            case "string":
                fs.add(a.set("product:" + id, json, SetArgs.Builder.ex(1800)));
                break;
            case "hash": {
                Map<String, String> m = fields(id);
                m.put("detail", detail);
                fs.add(a.hset("product:h:" + id, m));
                fs.add(a.expire("product:h:" + id, 1800));
                break;
            }
            case "hash-split":
                fs.add(a.hset("product:h:" + id, fields(id)));
                fs.add(a.expire("product:h:" + id, 1800));
                fs.add(a.set("detail:" + id, detail, SetArgs.Builder.ex(1800)));
                break;
            case "json":
                fs.add(a.dispatch(Cmd.JSON_SET, new StatusOutput<>(StringCodec.UTF8),
                        new CommandArgs<>(StringCodec.UTF8).addKey("product:" + id).add("$").add(json)));
                fs.add(a.expire("product:" + id, 1800));
                break;
        }
    }

    static Map<String, String> fields(int id) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(id));
        m.put("name", id + " 号商品");
        m.put("price", "399.00");
        m.put("stock", "500");
        return m;
    }

    /** 每个商品的详情各不相同（开头 6 位是编号），长度和 DETAIL 一样是 576 字节 */
    static String detail(int id) { return String.format("%06d", id) + DETAIL.substring(2); }

    static String json(int id, String detail) {
        return "{\"id\":" + id + ",\"name\":\"" + id + " 号商品\",\"price\":\"399.00\",\"stock\":500,\"detail\":\"" + detail + "\"}";
    }

    enum Cmd implements ProtocolKeyword {
        JSON_SET("JSON.SET");
        final byte[] b;
        Cmd(String s) { b = s.getBytes(StandardCharsets.US_ASCII); }
        public byte[] getBytes() { return b; }
    }

    static String info(RedisCommands<String, String> r, String section, String field) {
        for (String line : r.info(section).split("\r\n"))
            if (line.startsWith(field + ":")) return line.substring(field.length() + 1);
        return "?";
    }

    static int bytes(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
