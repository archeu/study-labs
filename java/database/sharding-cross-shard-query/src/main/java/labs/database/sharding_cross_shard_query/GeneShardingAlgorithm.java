package labs.database.sharding_cross_shard_query;

import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.sharding.api.sharding.complex.ComplexKeysShardingValue;

import java.util.*;

/**
 * 订单号基因：新订单号的最低 3 位 = user_id % 8，所以只拿订单号也能算出在哪张表。
 *
 * <pre>
 *   有 user_id     → 用 user_id % 8
 *   只有新订单号    → 用 id % 8（id ≥ GENE_MIN 才带基因）
 *   只有老订单号    → 算不出来，返回全部目标（广播）；如果 legacyFallback=false，就照样 id % 8，演示“查错表”
 * </pre>
 * 同一个类既当库算法（target=db：slot % 2 → ds_x）又当表算法（target=table：slot → t_order_x），由 props 区分。
 */
public final class GeneShardingAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {

    /** 拆分那天以后发的号都 ≥ 这个值（雪花号左移 3 位再拼基因）。 */
    public static final long GENE_MIN = 1_000_000_000L << 3;

    private String target;
    private boolean legacyFallback;
    private String prefix;

    @Override
    public void init(Properties props) {
        target = props.getProperty("target", "table");
        legacyFallback = Boolean.parseBoolean(props.getProperty("legacyFallback", "true"));
        prefix = target.equals("db") ? "ds_" : props.getProperty("tablePrefix", "t_order_");
    }

    public static long geneId(long seq, long userId) {
        return ((1_000_000_000L + seq) << 3) | (userId % 8);
    }

    @Override
    public Collection<String> doSharding(Collection<String> available, ComplexKeysShardingValue<Comparable<?>> v) {
        Map<String, Collection<Comparable<?>>> m = v.getColumnNameAndShardingValuesMap();
        Set<Long> slots = new TreeSet<>();
        Collection<Comparable<?>> users = m.get("user_id");
        Collection<Comparable<?>> ids = m.get("id");
        if (users != null && !users.isEmpty()) {
            for (Comparable<?> u : users) slots.add(((Number) u).longValue() % 8);
        } else if (ids != null && !ids.isEmpty()) {
            for (Comparable<?> i : ids) {
                long id = ((Number) i).longValue();
                if (id < GENE_MIN && legacyFallback) return available;   // 老单号没有基因：只能问遍所有表
                slots.add(id % 8);
            }
        } else {
            return available;
        }
        List<String> result = new ArrayList<>();
        for (long s : slots) {
            String name = target.equals("db") ? prefix + (s % 2) : prefix + s;
            if (available.contains(name) && !result.contains(name)) result.add(name);
        }
        return result;
    }

    @Override
    public String getType() {
        return "GENE";
    }
}
