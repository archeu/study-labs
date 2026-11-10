package labs.jvm.jvm_container;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ForkJoinPool;

/** 打印 JVM 在当前 cgroup 里“看到”的 CPU 和内存，以及它据此定下的默认值。 */
public class WhatJvmSees {
    public static void main(String[] args) throws Exception {
        Runtime rt = Runtime.getRuntime();
        System.out.println("cgroup memory.max      = " + cg("memory.max"));
        System.out.println("cgroup cpu.max         = " + cg("cpu.max"));
        System.out.println("availableProcessors    = " + rt.availableProcessors());
        System.out.println("maxMemory (heap)       = " + rt.maxMemory() / (1024 * 1024) + " MB");
        System.out.println("commonPool parallelism = " + ForkJoinPool.commonPool().getParallelism());
        StringBuilder gcs = new StringBuilder();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcs.append(gc.getName()).append(" / ");
        }
        System.out.println("GC                     = " + gcs);
    }

    /** cgroup v2：/proc/self/cgroup 里 "0::/路径"，文件在 /sys/fs/cgroup/路径/ 下。 */
    static String cg(String file) {
        try {
            String line = Files.readAllLines(Paths.get("/proc/self/cgroup")).get(0);
            String path = line.substring(line.indexOf("::") + 2);
            return Files.readAllLines(Paths.get("/sys/fs/cgroup" + path, file)).get(0);
        } catch (Exception e) {
            return "(读不到：" + e.getClass().getSimpleName() + "，可能是 cgroup v1 或不在 Linux)";
        }
    }
}
