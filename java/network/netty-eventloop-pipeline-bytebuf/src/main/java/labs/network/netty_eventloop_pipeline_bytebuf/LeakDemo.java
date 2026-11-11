package labs.network.netty_eventloop_pipeline_bytebuf;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ResourceLeakDetector;

/**
 * 实验：处理器读完 ByteBuf 不 release，池化的堆外内存只涨不落；泄漏检测器在 GC 之后报 LEAK。
 *   参数 leak ：继承 ChannelInboundHandlerAdapter，读完不 release（默认）
 *   参数 fixed：继承 SimpleChannelInboundHandler，读完自动 release
 * 不起网络：EmbeddedChannel 把“收到一条消息”模拟成 writeInbound。
 */
public class LeakDemo {

    public static void main(String[] args) throws Exception {
        boolean leak = args.length == 0 || "leak".equals(args[0]);
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID); // 每个 buffer 都跟踪，默认 SIMPLE 只抽样 1/128
        ByteBufAllocator alloc = ByteBufAllocator.DEFAULT;

        EmbeddedChannel ch = new EmbeddedChannel(leak
                ? new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ((ByteBuf) msg).readLong();          // 读了，然后……就没有然后了
                    }
                }
                : new SimpleChannelInboundHandler<ByteBuf>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                        msg.readLong();                      // 返回后父类自动 release
                    }
                });

        System.out.println("模式=" + (leak ? "leak" : "fixed") + "  分配器=" + alloc.getClass().getSimpleName());
        for (int round = 1; round <= 5; round++) {
            for (int i = 0; i < 2000; i++) {
                ch.writeInbound(alloc.directBuffer(1024).writeLong(i).writeZero(1016));
            }
            System.out.printf("第 %d 轮收了 2000 条 1 KB 消息，堆外已用 %,d KB%n",
                    round, used(alloc) / 1024);
        }
        // 泄漏检测靠 GC：被回收却没 release 的 buffer，会在下一次分配时被报告
        System.gc();
        Thread.sleep(500);
        for (int i = 0; i < 100; i++) {
            alloc.directBuffer(16).release();
        }
        Thread.sleep(200);
        ch.finishAndReleaseAll();
    }

    static long used(ByteBufAllocator alloc) {
        return ((ByteBufAllocatorMetricProvider) alloc).metric().usedDirectMemory();
    }
}
