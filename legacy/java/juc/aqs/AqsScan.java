import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

// 扫 class 文件：谁直接继承了 AQS，谁引用了基于 AQS 的同步器
// java AqsScan.java jdk            扫当前 JDK 的 java.base
// java AqsScan.java a.jar b.jar    扫 jar
public class AqsScan {
  static final String AQS = "java/util/concurrent/locks/AbstractQueuedSynchronizer", AQLS = "java/util/concurrent/locks/AbstractQueuedLongSynchronizer";
  static final String[] USED = {"java/util/concurrent/locks/ReentrantLock", "java/util/concurrent/locks/ReentrantReadWriteLock", "java/util/concurrent/Semaphore",
      "java/util/concurrent/CountDownLatch", "java/util/concurrent/CyclicBarrier", "java/util/concurrent/locks/Condition", "jdk/internal/misc/InternalLock"};
  static Map<String, Set<String>> users = new TreeMap<>(); static List<String> subs = new ArrayList<>(); static int classes = 0;
  static void scan(String name, byte[] b) throws IOException {
    var in = new DataInputStream(new ByteArrayInputStream(b));
    if (in.readInt() != 0xCAFEBABE) return; in.readUnsignedShort(); in.readUnsignedShort();
    int n = in.readUnsignedShort(); String[] utf = new String[n]; int[] cls = new int[n];
    for (int i = 1; i < n; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1: utf[i] = in.readUTF(); break;
        case 7: cls[i] = in.readUnsignedShort(); break;
        case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
        case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
        case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
        case 5: case 6: in.readLong(); i++; break;
        default: throw new IOException("tag " + tag + " in " + name);
      }
    }
    in.readUnsignedShort(); int self = in.readUnsignedShort(), sup = in.readUnsignedShort();
    String me = utf[cls[self]], parent = sup == 0 ? "" : utf[cls[sup]];
    classes++;
    if (parent.equals(AQS) || parent.equals(AQLS)) subs.add(me.replace('/', '.') + (parent.equals(AQLS) ? "  (Long)" : ""));
    for (int i = 1; i < n; i++) if (cls[i] != 0) for (String u : USED) if (utf[cls[i]].equals(u)) users.computeIfAbsent(u.substring(u.lastIndexOf('/') + 1), k -> new TreeSet<>()).add(me.replace('/', '.'));
  }
  public static void main(String[] a) throws Exception {
    if (a[0].equals("jdk")) {
      FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
      try (var w = Files.walk(fs.getPath("/modules/java.base"))) {
        for (Path p : (Iterable<Path>) w::iterator) if (p.toString().endsWith(".class")) scan(p.toString(), Files.readAllBytes(p));
      }
      System.out.println("JDK " + Runtime.version() + " java.base，" + classes + " 个类");
    } else {
      for (String j : a) try (var z = new ZipFile(j)) {
        for (var e : Collections.list(z.entries())) if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/versions/")) scan(e.getName(), z.getInputStream(e).readAllBytes());
      }
      System.out.println(a.length + " 个 jar，" + classes + " 个类");
    }
    System.out.println("直接继承 AQS 的：" + subs.size()); subs.forEach(s -> System.out.println("  " + s));
    users.forEach((k, v) -> { System.out.println("引用 " + k + " 的：" + v.size()); v.forEach(s -> System.out.println("  " + s)); });
  }
}
