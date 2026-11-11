package labs.network.netty_eventloop_pipeline_bytebuf;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;

import java.util.concurrent.*;

/**
 * 实验：在 EventLoop 线程里做阻塞的事，会拖慢同一个线程上的其他连接。
 * 服务端只有 1 个 worker（EventLoop）线程。连接 A 发“慢请求”（处理器里 sleep 200 ms 模拟查库），
 * 10 ms 后连接 B 发“快请求”，量 B 的往返时间。
 *   参数 inline  ：业务处理器直接跑在 EventLoop 上（默认）
 *   参数 offload ：addLast(业务线程组, handler)，业务处理器跑在另一组线程上
 */
public class BlockingEventLoop {

    public static void main(String[] args) throws Exception {
        boolean offload = args.length > 0 && "offload".equals(args[0]);
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventExecutorGroup biz = new DefaultEventExecutorGroup(4);
        EventLoopGroup clients = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        try {
            new ServerBootstrap().group(boss, worker).channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(64, 0, 4, 0, 4))
                                         .addLast(new LengthFieldPrepender(4));
                            if (offload) {
                                ch.pipeline().addLast(biz, "biz", new BizHandler());
                            } else {
                                ch.pipeline().addLast("biz", new BizHandler());
                            }
                        }
                    }).bind(9091).sync();

            BlockingQueue<Long> bReplies = new LinkedBlockingQueue<>();
            Channel a = connect(clients, null);
            Channel b = connect(clients, bReplies);

            long[] rtt = new long[7];
            for (int i = 0; i < rtt.length; i++) {
                a.writeAndFlush(frame('S'));
                Thread.sleep(10);
                long t0 = System.nanoTime();
                b.writeAndFlush(frame('F'));
                rtt[i] = (bReplies.take() - t0) / 1_000_000;
                Thread.sleep(250);              // 等 A 的慢请求做完，下一轮重新开始
            }
            java.util.Arrays.sort(rtt);
            System.out.printf("模式=%s  B 的快请求往返（ms，7 轮排序后）：%s  中位数=%d ms%n",
                    offload ? "offload" : "inline", java.util.Arrays.toString(rtt), rtt[3]);
        } finally {
            clients.shutdownGracefully();
            biz.shutdownGracefully();
            worker.shutdownGracefully();
            boss.shutdownGracefully();
        }
    }

    static ByteBuf frame(char op) {
        return Unpooled.buffer(1).writeByte(op);
    }

    static Channel connect(EventLoopGroup group, BlockingQueue<Long> sink) throws InterruptedException {
        return new Bootstrap().group(group).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(64, 0, 4, 0, 4))
                                     .addLast(new LengthFieldPrepender(4))
                                     .addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                         @Override
                                         protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                             if (sink != null) sink.add(System.nanoTime());
                                         }
                                     });
                    }
                }).connect("127.0.0.1", 9091).sync().channel();
    }

    static class BizHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private boolean printed;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            byte op = msg.readByte();
            if (!printed) {
                printed = true;
                System.out.printf("  请求 %c 由线程 %s 处理（连接 %s）%n", (char) op,
                        Thread.currentThread().getName(), ctx.channel().remoteAddress());
            }
            if (op == 'S') {
                Thread.sleep(200);              // 模拟同步查库、等锁
            }
            ctx.writeAndFlush(ctx.alloc().buffer(1).writeByte(op));
        }
    }
}
