package labs.algo.hash_table;

/**
 * long 原语的开放寻址哈希集合（线性探测 + 墓碑）。幂等用：这个单号见过没有。
 * 没有 Node、没有 Long 装箱：一个 key 只占 long[] 里的 8 字节加 byte[] 里的 1 字节。
 */
public class LongOpenHashSet {
    static final byte EMPTY = 0, FULL = 1, TOMB = 2;   // 空、有人、墓碑（删过）

    long[] keys;
    byte[] state;
    int mask, size, used;            // used = FULL + TOMB，决定什么时候扩容
    final double maxLoad;
    final boolean mix;               // 是否先把 key 打散（见 ⑥ 雪花 ID 那个坑）
    public long probes;              // 累计探测次数，测负载因子用
    public boolean trace;

    public LongOpenHashSet(int capacityPow2, double maxLoad, boolean mix) {
        keys = new long[capacityPow2];
        state = new byte[capacityPow2];
        mask = capacityPow2 - 1;
        this.maxLoad = maxLoad;
        this.mix = mix;
    }

    /** MurmurHash3 的 fmix64：每一位输入都影响每一位输出。 */
    static long fmix64(long k) {
        k ^= k >>> 33; k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33; k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    int home(long key) { return (int) ((mix ? fmix64(key) : key) & mask); }

    /** 放进去；已经有了返回 false（重复单）。 */
    public boolean add(long key) {
        int i = home(key), firstTomb = -1;
        StringBuilder path = trace ? new StringBuilder() : null;
        while (true) {
            probes++;
            if (trace) path.append(i).append(' ');
            if (state[i] == EMPTY) break;
            if (state[i] == TOMB) { if (firstTomb < 0) firstTomb = i; }
            else if (keys[i] == key) { if (trace) System.out.printf("add %-5d 探测 %s→ 已经有了%n", key, path); return false; }
            i = (i + 1) & mask;
        }
        int at = firstTomb >= 0 ? firstTomb : i;          // 墓碑可以复用
        if (state[at] == EMPTY) used++;
        keys[at] = key; state[at] = FULL; size++;
        if (trace) System.out.printf("add %-5d 探测 %s→ 放在 %d   %s%n", key, path, at, dump());
        if (used > maxLoad * keys.length) resize();
        return true;
    }

    public boolean contains(long key) {
        int i = home(key);
        StringBuilder path = trace ? new StringBuilder() : null;
        while (true) {
            probes++;
            if (trace) path.append(i).append(' ');
            if (state[i] == EMPTY) { if (trace) System.out.printf("找 %-5d 探测 %s→ 碰到空格，没有%n", key, path); return false; }
            if (state[i] == FULL && keys[i] == key) { if (trace) System.out.printf("找 %-5d 探测 %s→ 在 %d%n", key, path, i); return true; }
            i = (i + 1) & mask;                            // 墓碑和别人：接着往后找
        }
    }

    /** 删除：留墓碑。tombstone=false 时直接置空，演示 ⑥ 的坑。 */
    public boolean remove(long key, boolean tombstone) {
        int i = home(key);
        while (state[i] != EMPTY) {
            if (state[i] == FULL && keys[i] == key) {
                state[i] = tombstone ? TOMB : EMPTY; size--;
                if (!tombstone) used--;
                if (trace) System.out.printf("删 %-5d 位置 %d 改成%s   %s%n", key, i, tombstone ? "墓碑" : "空格", dump());
                return true;
            }
            i = (i + 1) & mask;
        }
        return false;
    }

    void resize() {
        long[] ok = keys; byte[] os = state;
        keys = new long[ok.length * 2]; state = new byte[ok.length * 2];
        mask = keys.length - 1; size = 0; used = 0;
        boolean t = trace; trace = false;
        for (int i = 0; i < ok.length; i++) if (os[i] == FULL) add(ok[i]);   // 墓碑在这里被清掉
        trace = t;
        if (trace) System.out.printf("扩容到 %d 格  %s%n", keys.length, dump());
    }

    public int size() { return size; }

    String dump() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < keys.length; i++) {
            sb.append(i == 0 ? "" : " ");
            sb.append(state[i] == EMPTY ? "·" : state[i] == TOMB ? "✝" : String.valueOf(keys[i]));
        }
        return sb.append(']').toString();
    }

    /** 和页面 ④ 的分步动画同一组数据：8 格，不打散，下标 = key & 7。 */
    public static void main(String[] args) {
        LongOpenHashSet s = new LongOpenHashSet(8, 0.8, false);
        s.trace = true;
        for (long k : new long[]{1024, 7781, 3, 11, 88, 27}) s.add(k);
        s.contains(27);
        s.remove(11, true);
        s.contains(27);
        s.add(27);                     // 重复单：要能认出来
        System.out.println("\n-- 换成删除时直接置空：");
        LongOpenHashSet b = new LongOpenHashSet(8, 0.8, false);
        for (long k : new long[]{1024, 7781, 3, 11, 88, 27}) b.add(k);
        b.trace = true;
        b.remove(11, false);
        b.contains(27);
        b.add(27);                     // 没认出来：同一单放进去两次
        System.out.println("集合里 27 出现了 " + java.util.Arrays.stream(b.keys).filter(x -> x == 27).count() + " 次");
    }
}
