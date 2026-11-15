package labs.search.es_inverted_index_shards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * 商家 301 搜“键盘”：Elasticsearch 9.5 单节点上的几组实验，全部用 REST API（JDK HttpClient）。
 *
 * <pre>
 *   setup    建索引 orders（3 个主分片、0 副本），mapping 里 title 用 cjk 分词、shop_id / status 用 keyword
 *   load     bulk 写 10 万单，看每个分片分到多少
 *   analyze  同一句“88 号机械键盘 青轴”，standard 和 cjk 两个分词器各切成什么
 *   search   商家 301 + 标题含“键盘” + 待发货 + 按时间倒序：DSL、耗时、命中数
 *   nrt      写一单马上搜：默认 1 s refresh 下多久能搜到；refresh=wait_for 时写入要等多久；30 s 没人搜以后的第一次搜索
 *   deep     from + size 超过 1 万直接报错；search_after 接着翻
 *   mapping  不建 mapping 直接写：status 被动态映射成 text，term 查 "WAIT_SHIP" 查不到
 * </pre>
 */
public class EsLab {

    static final String ES = System.getProperty("es", "http://127.0.0.1:19200");
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final ObjectMapper M = new ObjectMapper();
    static final String[] TITLES = {"88 号机械键盘 青轴", "88 号机械键盘 红轴", "无线鼠标 静音", "游戏键盘 RGB 背光", "27 寸显示器",
            "键盘托架", "机械键盘 键帽套装", "蓝牙耳机", "鼠标垫 加厚", "显示器支架", "键帽 单个", "移动硬盘 1TB"};
    static final String[] NAMES = {"张伟", "王芳", "李娜", "张敏", "刘洋", "陈静", "杨磊", "赵强"};
    static final String[] STATUS = {"WAIT_PAY", "WAIT_SHIP", "SHIPPED", "DONE"};

    public static void main(String[] args) throws Exception {
        List<String> modes = args.length > 0 && !args[0].equals("all") ? List.of(args)
                : List.of("setup", "load", "analyze", "search", "nrt", "deep", "mapping");
        for (String m : modes) {
            switch (m) {
                case "setup" -> setup();
                case "load" -> load();
                case "analyze" -> analyze();
                case "search" -> search();
                case "nrt" -> nrt();
                case "deep" -> deep();
                case "mapping" -> mapping();
                default -> throw new IllegalArgumentException(m);
            }
        }
    }

    // ---------------------------------------------------------------- HTTP
    record Res(int code, JsonNode body, double ms) {}

    static Res call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(ES + path)).header("Content-Type",
                path.contains("_bulk") ? "application/x-ndjson" : "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        long t0 = System.nanoTime();
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        double ms = (System.nanoTime() - t0) / 1e6;
        String body = r.body();
        JsonNode n = body.isEmpty() || !(body.startsWith("{") || body.startsWith("[")) ? M.createObjectNode().put("text", body) : M.readTree(body);
        return new Res(r.statusCode(), n, ms);
    }

    static void title(String t) {
        System.out.println();
        System.out.println("==== " + t);
    }

    // ---------------------------------------------------------------- setup / load
    static void setup() throws Exception {
        call("DELETE", "/orders", null);
        Res r = call("PUT", "/orders", """
                {
                  "settings": { "number_of_shards": 3, "number_of_replicas": 0 },
                  "mappings": { "properties": {
                    "order_id":     { "type": "long" },
                    "user_id":      { "type": "long" },
                    "shop_id":      { "type": "keyword" },
                    "status":       { "type": "keyword" },
                    "title":        { "type": "text", "analyzer": "cjk", "fields": { "std": { "type": "text", "analyzer": "standard" } } },
                    "receiver":     { "type": "text", "analyzer": "cjk" },
                    "amount_cents": { "type": "long" },
                    "created_at":   { "type": "date" }
                  } }
                }""");
        title("setup：建索引 orders（3 主分片、0 副本）→ " + r.code + " " + r.body.path("acknowledged"));
    }

    static String doc(long id, Random r) {
        long user = 1000 + r.nextInt(50000);
        return String.format("{\"order_id\":%d,\"user_id\":%d,\"shop_id\":\"%d\",\"status\":\"%s\",\"title\":\"%s\",\"receiver\":\"%s\",\"amount_cents\":%d,\"created_at\":\"2026-11-%02dT%02d:%02d:%02dZ\"}",
                id, user, 301 + r.nextInt(50), STATUS[r.nextInt(4)], TITLES[r.nextInt(TITLES.length)], NAMES[r.nextInt(NAMES.length)],
                1000 + r.nextInt(90000), 1 + r.nextInt(14), r.nextInt(24), r.nextInt(60), r.nextInt(60));
    }

    static void load() throws Exception {
        Random r = new Random(1024);
        long t0 = System.nanoTime();
        int n = 100_000;
        for (int from = 0; from < n; from += 5000) {
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < from + 5000; i++) {
                long id = 10_000_000L + i;
                sb.append("{\"index\":{\"_index\":\"orders\",\"_id\":\"").append(id).append("\"}}\n").append(doc(id, r)).append('\n');
            }
            Res b = call("POST", "/_bulk", sb.toString());
            if (b.body.path("errors").asBoolean()) throw new IllegalStateException("bulk errors: " + b.body.toString().substring(0, 300));
        }
        call("POST", "/orders/_refresh", null);
        title(String.format("load：bulk 写 %d 单，%.1f s", n, (System.nanoTime() - t0) / 1e9));
        Res s = call("GET", "/_cat/shards/orders?format=json&h=shard,prirep,docs,store", null);
        for (JsonNode x : s.body) System.out.println("   分片 " + x.path("shard").asText() + "（" + x.path("prirep").asText() + "）：" + x.path("docs").asText() + " 个文档，" + x.path("store").asText());
    }

    // ---------------------------------------------------------------- analyze
    static void analyze() throws Exception {
        title("analyze：“88 号机械键盘 青轴”被切成哪些词");
        for (String a : new String[]{"standard", "cjk"}) {
            Res r = call("POST", "/_analyze", "{\"analyzer\":\"" + a + "\",\"text\":\"88 号机械键盘 青轴\"}");
            List<String> toks = new ArrayList<>();
            r.body.path("tokens").forEach(t -> toks.add(t.path("token").asText()));
            System.out.println("   " + a + "：" + toks);
        }
    }

    // ---------------------------------------------------------------- search
    static final String SHOP_QUERY = """
            {
              "query": { "bool": {
                "filter": [ { "term": { "shop_id": "301" } }, { "term": { "status": "WAIT_SHIP" } } ],
                "must":   [ { "match": { "title": "键盘" } } ]
              } },
              "sort": [ { "created_at": "desc" }, { "order_id": "desc" } ],
              "size": 5,
              "_source": [ "order_id", "title", "created_at" ]
            }""";

    static void search() throws Exception {
        title("search：商家 301 + 待发货 + 标题含“键盘”，按时间倒序取 5 条");
        Res r = null;
        for (int i = 0; i < 4; i++) r = call("POST", "/orders/_search", SHOP_QUERY);   // 前几次预热，看最后一次
        System.out.println("   took " + r.body.path("took").asInt() + " ms（ES 内部），往返 " + String.format("%.1f", r.ms) + " ms；命中 "
                + r.body.path("hits").path("total").path("value").asInt() + "（" + r.body.path("hits").path("total").path("relation").asText() + "）；问了 "
                + r.body.path("_shards").path("total").asInt() + " 个分片");
        for (JsonNode h : r.body.path("hits").path("hits"))
            System.out.println("   " + h.path("_source").path("order_id").asText() + "  " + h.path("_source").path("title").asText() + "  " + h.path("_source").path("created_at").asText());
        Res c = call("POST", "/orders/_count", "{\"query\":{\"match\":{\"title\":\"键盘\"}}}");
        Res c2 = call("POST", "/orders/_count", "{\"query\":{\"match_phrase\":{\"title\":\"机械键盘\"}}}");
        System.out.println("   全店：标题含“键盘”" + c.body.path("count").asInt() + " 单；短语“机械键盘”" + c2.body.path("count").asInt() + " 单");
        Res c3 = call("POST", "/orders/_count", "{\"query\":{\"match\":{\"title.std\":\"键盘\"}}}");
        Res c4 = call("POST", "/orders/_count", "{\"query\":{\"match\":{\"title.std\":{\"query\":\"键盘\",\"operator\":\"and\"}}}}");
        System.out.println("   同样搜“键盘”，standard 分词的 title.std：" + c3.body.path("count").asInt() + " 单（混进了“键帽”“硬盘”）；operator=and：" + c4.body.path("count").asInt() + " 单");
    }

    // ---------------------------------------------------------------- nrt
    static boolean visible(long id) throws Exception {
        Res r = call("POST", "/orders/_search", "{\"query\":{\"term\":{\"order_id\":" + id + "}},\"size\":0}");
        return r.body.path("hits").path("total").path("value").asInt() > 0;
    }

    static void nrt() throws Exception {
        Random r = new Random(9527);
        title("nrt 1：默认 refresh（1 s），写入后每 20 ms 搜一次，多久能搜到（10 次）");
        List<Double> lat = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long id = 90_000_000L + i;
            call("PUT", "/orders/_doc/" + id, doc(id, r));
            long t0 = System.nanoTime();
            while (!visible(id)) Thread.sleep(20);
            lat.add((System.nanoTime() - t0) / 1e6);
            Thread.sleep(137);   // 错开 refresh 周期
        }
        Collections.sort(lat);
        System.out.printf("   最快 %.0f ms，中位 %.0f ms，最慢 %.0f ms%n", lat.get(0), lat.get(lat.size() / 2), lat.get(lat.size() - 1));

        title("nrt 2：写入时带 ?refresh=wait_for，写请求本身要等多久（10 次）");
        List<Double> w = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long id = 91_000_000L + i;
            Res x = call("PUT", "/orders/_doc/" + id + "?refresh=wait_for", doc(id, r));
            w.add(x.ms);
            if (!visible(id)) System.out.println("   ！返回后仍搜不到 " + id);
            Thread.sleep(137);
        }
        Collections.sort(w);
        System.out.printf("   写请求耗时：最快 %.0f ms，中位 %.0f ms，最慢 %.0f ms；返回后立刻搜，都能搜到%n", w.get(0), w.get(w.size() / 2), w.get(w.size() - 1));

        title("nrt 3：35 s 没有任何搜索（分片进入 search idle），再写一单、马上搜一次");
        Thread.sleep(35_000);
        long id = 92_000_000L;
        call("PUT", "/orders/_doc/" + id, doc(id, r));
        long t0 = System.nanoTime();
        boolean v = visible(id);
        System.out.printf("   第一次搜索就%s，这次搜索耗时 %.0f ms%n", v ? "搜到了" : "没搜到", (System.nanoTime() - t0) / 1e6);
        while (!visible(id)) Thread.sleep(20);
        System.out.printf("   接着每 20 ms 搜一次：写入后 %.0f ms 搜到%n", (System.nanoTime() - t0) / 1e6);
        Res st = call("GET", "/orders/_settings?include_defaults=true&filter_path=**.refresh_interval,**.search.idle.after", null);
        System.out.println("   相关设置：" + st.body);
    }

    // ---------------------------------------------------------------- deep paging
    static void deep() throws Exception {
        title("deep：from = 10000, size = 10");
        Res r = call("POST", "/orders/_search", "{\"from\":10000,\"size\":10,\"query\":{\"match_all\":{}},\"sort\":[{\"created_at\":\"desc\"},{\"order_id\":\"desc\"}]}");
        System.out.println("   → " + r.code + " " + r.body.path("error").path("root_cause").path(0).path("reason").asText());
        title("deep：search_after 一页一页往后翻（每页 1000，翻 12 页）");
        String after = null;
        long t0 = System.nanoTime();
        int got = 0;
        for (int p = 0; p < 12; p++) {
            String q = "{\"size\":1000,\"query\":{\"match_all\":{}},\"sort\":[{\"created_at\":\"desc\"},{\"order_id\":\"desc\"}],\"_source\":false"
                    + (after == null ? "" : ",\"search_after\":" + after) + "}";
            Res x = call("POST", "/orders/_search", q);
            JsonNode hits = x.body.path("hits").path("hits");
            got += hits.size();
            after = hits.get(hits.size() - 1).path("sort").toString();
        }
        System.out.printf("   翻过 %d 条，%.0f ms；最后一页的游标：%s%n", got, (System.nanoTime() - t0) / 1e6, after);
    }

    // ---------------------------------------------------------------- mapping pitfall
    static void mapping() throws Exception {
        title("mapping：不建 mapping 直接写一单，status = \"WAIT_SHIP\"");
        call("DELETE", "/orders_dyn", null);
        call("PUT", "/orders_dyn/_doc/1?refresh=true", "{\"order_id\":9527,\"shop_id\":\"301\",\"status\":\"WAIT_SHIP\"}");
        Res m = call("GET", "/orders_dyn/_mapping", null);
        System.out.println("   动态映射出来的 status：" + m.body.path("orders_dyn").path("mappings").path("properties").path("status"));
        for (String f : new String[]{"status", "status.keyword"}) {
            Res r = call("POST", "/orders_dyn/_count", "{\"query\":{\"term\":{\"" + f + "\":\"WAIT_SHIP\"}}}");
            System.out.println("   term " + f + " = \"WAIT_SHIP\" → " + r.body.path("count").asInt() + " 条");
        }
        Res a = call("POST", "/orders_dyn/_analyze", "{\"field\":\"status\",\"text\":\"WAIT_SHIP\"}");
        List<String> toks = new ArrayList<>();
        a.body.path("tokens").forEach(t -> toks.add(t.path("token").asText()));
        System.out.println("   status 字段里实际存的词：" + toks);
        call("DELETE", "/orders_dyn", null);
    }
}
