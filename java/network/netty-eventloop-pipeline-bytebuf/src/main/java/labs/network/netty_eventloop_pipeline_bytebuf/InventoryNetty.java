package labs.network.netty_eventloop_pipeline_bytebuf;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 页面⑤：用 Netty 写一个最小的“库存预占”服务和客户端。
 * 协议：每帧 = 4 字节长度 + 内容；请求内容 = skuId(long 8) + count(int 4)，响应内容 = ok(byte 1) + 剩余库存(int 4)。
 * 运行后看三件事：哪个线程在干活、处理器的执行顺序、一次 write 里塞了 3 个请求也能被正确切开。
 */
public class InventoryNetty {

    static final Map<Long, Integer> STOCK = new ConcurrentHashMap<>();

    static void log(String who, String what) {
        System.out.printf("%-26s %-12s %s%n", Thread.currentThread().getName(), who, what);
    }

    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("main");
        STOCK.put(88L, 5);
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        EventLoopGroup clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        try {
            // ---------- 服务端：库存服务 ----------
            ServerBootstrap sb = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                              // 入站：head → frame → reserve；出站：reserve 调 ctx.write → prepender → head
                              .addLast("frame", new LengthFieldBasedFrameDecoder(1024, 0, 4, 0, 4))
                              .addLast("prepender", new LengthFieldPrepender(4))
                              .addLast("reserve", new ReserveHandler());
                        }
                    });
            Channel server = sb.bind(9090).sync().channel();
            log("main", "库存服务监听 9090，worker 线程数 2");

            // ---------- 客户端：订单服务 ----------
            CountDownLatch replies = new CountDownLatch(3);
            Bootstrap b = new Bootstrap()
                    .group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                              .addLast(new LengthFieldBasedFrameDecoder(1024, 0, 4, 0, 4))
                              .addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                  @Override
                                  protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                      boolean ok = msg.readByte() == 1;
                                      log("client", "收到响应 ok=" + ok + " 剩余=" + msg.readInt());
                                      replies.countDown();
                                  }
                              });
                        }
                    });
            Channel ch = b.connect("127.0.0.1", 9090).sync().channel();

            // 故意把 3 个请求塞进同一个 ByteBuf、一次写出去：TCP 上就是一串没有边界的字节
            ByteBuf out = ch.alloc().buffer();
            for (int count : new int[]{2, 2, 2}) {
                out.writeInt(12).writeLong(88L).writeInt(count);
            }
            log("main", "一次写出 " + out.readableBytes() + " 字节（3 个请求），"
                    + out.getClass().getSimpleName() + " direct=" + out.isDirect());
            ch.writeAndFlush(out);   // main 线程调用，Netty 把它转成任务交给这条连接的 EventLoop

            replies.await();
            ch.close().sync();
            server.close().sync();
        } finally {
            clientGroup.shutdownGracefully();
            workers.shutdownGracefully();
            boss.shutdownGracefully();
        }
    }

    /** 业务处理器：解出一帧 → 扣库存 → 回一帧。SimpleChannelInboundHandler 读完后会自动 release 入站的 ByteBuf。 */
    static class ReserveHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            log("reserve", "新连接 " + ctx.channel().remoteAddress() + " 绑定到这个线程");
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            log("reserve", "readerIndex=" + frame.readerIndex() + " writerIndex=" + frame.writerIndex());
            long sku = frame.readLong();
            int count = frame.readInt();
            boolean[] hit = {false};   // 多条连接可能落在不同 worker 线程上，扣减要原子
            int left = STOCK.compute(sku, (k, v) -> {
                int cur = v == null ? 0 : v;
                if (cur >= count) { hit[0] = true; return cur - count; }
                return cur;
            });
            boolean ok = hit[0];
            log("reserve", "预占 sku=" + sku + " x" + count + " → " + (ok ? "成功" : "库存不够"));
            ByteBuf resp = ctx.alloc().buffer(5).writeByte(ok ? 1 : 0).writeInt(left);
            ctx.writeAndFlush(resp);  // 从当前位置往 head 走，会经过 prepender 补上 4 字节长度
        }
    }
}
