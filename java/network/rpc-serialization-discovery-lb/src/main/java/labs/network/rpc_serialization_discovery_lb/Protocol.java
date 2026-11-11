package labs.network.rpc_serialization_discovery_lb;

import java.io.*;

/**
 * 协议：在 TCP 字节流上切出一帧一帧的消息。17 字节定长头 + 变长体：
 *
 *   魔数 2 | 版本 1 | 类型 1 | 序列化 id 1 | 请求 id 8 | 体长度 4 | 体 N
 *
 * 魔数：认出“这是我的协议”，连错端口立刻发现。长度：TCP 不管消息边界，必须自己标。
 * 请求 id：一条连接上同时有很多请求在途，响应回来时靠它找到是谁在等。
 */
public final class Protocol {
    private Protocol() {}

    public static final short MAGIC = (short) 0xCAFE;
    public static final int HEADER_LENGTH = 17;
    public static final byte REQUEST = 0, RESPONSE = 1;
    public static final int MAX_BODY = 4 * 1024 * 1024;   // 防止坏数据让对面分配超大数组

    public static final class Frame {
        public final byte type, serializer;
        public final long requestId;
        public final byte[] body;
        public Frame(byte type, byte serializer, long requestId, byte[] body) {
            this.type = type; this.serializer = serializer; this.requestId = requestId; this.body = body;
        }
    }

    public static void write(DataOutputStream out, Frame f) throws IOException {
        out.writeShort(MAGIC);
        out.writeByte(1);
        out.writeByte(f.type);
        out.writeByte(f.serializer);
        out.writeLong(f.requestId);
        out.writeInt(f.body.length);
        out.write(f.body);
        out.flush();
    }

    /** readFully：不管 TCP 把字节拆成几段送来，都凑够头和体再返回。 */
    public static Frame read(DataInputStream in) throws IOException {
        short magic = in.readShort();
        if (magic != MAGIC) throw new IOException(String.format("魔数不对：%04x，不是本协议的连接", magic));
        in.readByte();                         // 版本
        byte type = in.readByte(), ser = in.readByte();
        long id = in.readLong();
        int len = in.readInt();
        if (len < 0 || len > MAX_BODY) throw new IOException("体长度异常：" + len);
        byte[] body = new byte[len];
        in.readFully(body);
        return new Frame(type, ser, id, body);
    }

    public static Serializers.Serializer serializer(byte id) {
        return id == 1 ? Serializers.JDK : Serializers.COMPACT;
    }
}
