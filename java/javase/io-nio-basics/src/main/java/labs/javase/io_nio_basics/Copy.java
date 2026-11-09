package labs.javase.io_nio_basics;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/**
 * 同一个文件，五种复制法。配合 strace 数系统调用：
 *   strace -f -c -e trace=read,write,sendfile,copy_file_range java -cp target/classes labs.javase.io_nio_basics.Copy <模式> <源> <目标>
 * 模式：none（只启动 JVM，当基线）、byte、buffered、array、files、transfer
 */
public class Copy {
    public static void main(String[] args) throws IOException {
        String mode = args[0];
        if (mode.equals("none")) return;
        Path src = Path.of(args[1]), dst = Path.of(args[2]);
        long t = System.nanoTime();
        switch (mode) {
            case "byte" -> {                                     // 一次一个字节：每次 read() 都是一次系统调用
                try (InputStream in = new FileInputStream(src.toFile()); OutputStream out = new FileOutputStream(dst.toFile())) {
                    int b; while ((b = in.read()) != -1) out.write(b);
                }
            }
            case "buffered" -> {                                 // 还是一次一个字节，但隔着 8 KB 缓冲
                try (InputStream in = new BufferedInputStream(new FileInputStream(src.toFile()));
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(dst.toFile()))) {
                    int b; while ((b = in.read()) != -1) out.write(b);
                }
            }
            case "array" -> {                                    // 自己拿 8 KB 数组搬
                try (InputStream in = new FileInputStream(src.toFile()); OutputStream out = new FileOutputStream(dst.toFile())) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
            case "files" -> Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            case "transfer" -> {                                 // 让内核直接搬，数据不进 JVM 堆
                try (FileChannel in = FileChannel.open(src, StandardOpenOption.READ);
                     FileChannel out = FileChannel.open(dst, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    long pos = 0, size = in.size();
                    while (pos < size) pos += in.transferTo(pos, size - pos, out);
                }
            }
            default -> throw new IllegalArgumentException(mode);
        }
        System.out.printf("%-9s %8.1f ms  %d 字节%n", mode, (System.nanoTime() - t) / 1e6, Files.size(dst));
    }
}
