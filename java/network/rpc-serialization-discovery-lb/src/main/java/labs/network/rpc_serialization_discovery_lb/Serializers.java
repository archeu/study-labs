package labs.network.rpc_serialization_discovery_lb;

import java.io.*;

/**
 * 序列化：对象 ⇄ 字节。两种做法对照：
 *  - JDK：自描述，类名、字段名、类型都写进字节流，对面不用提前知道结构，但又大又只认 Java。
 *  - Compact：两边事先约定好结构（相当于 .proto），只写“第几个字段 + 值”，整数用 varint。
 */
public final class Serializers {
    private Serializers() {}

    public interface Serializer {
        byte id();
        byte[] write(Object o) throws IOException;
        Object read(byte[] b) throws IOException;
    }

    public static final Serializer JDK = new Serializer() {
        public byte id() { return 1; }
        public byte[] write(Object o) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(o);
            oos.close();
            return bos.toByteArray();
        }
        public Object read(byte[] b) throws IOException {
            // 生产代码里反序列化不可信数据要加 ObjectInputFilter，见页面 ⑥
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(b));
            try { return ois.readObject(); } catch (ClassNotFoundException e) { throw new IOException(e); }
        }
    };

    /** 只认识本例用到的几种类型。每个值前面 1 字节类型标记，结构体内部用“字段号 + varint”。 */
    public static final Serializer COMPACT = new Serializer() {
        public byte id() { return 2; }
        public byte[] write(Object o) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            writeValue(new DataOutputStream(bos), o);
            return bos.toByteArray();
        }
        public Object read(byte[] b) throws IOException {
            return readValue(new DataInputStream(new ByteArrayInputStream(b)));
        }
    };

    // ---- Compact 的编码细节 ----
    static void writeValue(DataOutputStream out, Object o) throws IOException {
        if (o instanceof Api.ReserveRequest) {
            Api.ReserveRequest r = (Api.ReserveRequest) o;
            out.writeByte('Q');
            out.writeByte(1 << 3); varint(out, r.orderId);   // 字段 1
            out.writeByte(2 << 3); varint(out, r.skuId);     // 字段 2
            out.writeByte(3 << 3); varint(out, r.qty);       // 字段 3
            out.writeByte(0);                                // 结束
        } else if (o instanceof Api.ReserveReply) {
            Api.ReserveReply r = (Api.ReserveReply) o;
            out.writeByte('P'); out.writeBoolean(r.ok); out.writeUTF(r.instance);
        } else if (o instanceof RpcMessage.Request) {
            RpcMessage.Request q = (RpcMessage.Request) o;
            out.writeByte('R'); out.writeUTF(q.service); out.writeUTF(q.method);
            out.writeByte(q.args.length);
            for (Object a : q.args) writeValue(out, a);
        } else if (o instanceof RpcMessage.Response) {
            RpcMessage.Response p = (RpcMessage.Response) o;
            out.writeByte('S'); out.writeBoolean(p.error != null);
            if (p.error != null) out.writeUTF(p.error); else writeValue(out, p.value);
        } else {
            throw new IOException("Compact 不认识这个类型：" + o.getClass().getName());
        }
    }

    static Object readValue(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        switch (tag) {
            case 'Q': {
                long orderId = 0, skuId = 0; int qty = 0;
                for (int key; (key = in.readUnsignedByte()) != 0; ) {
                    long v = readVarint(in);
                    switch (key >>> 3) {             // 不认识的字段号直接跳过：新老版本能互通
                        case 1: orderId = v; break;
                        case 2: skuId = v; break;
                        case 3: qty = (int) v; break;
                        default: break;
                    }
                }
                return new Api.ReserveRequest(orderId, skuId, qty);
            }
            case 'P': return new Api.ReserveReply(in.readBoolean(), in.readUTF());
            case 'R': {
                String service = in.readUTF(), method = in.readUTF();
                Object[] args = new Object[in.readByte()];
                for (int i = 0; i < args.length; i++) args[i] = readValue(in);
                return new RpcMessage.Request(service, method, args);
            }
            case 'S': {
                boolean err = in.readBoolean();
                return err ? RpcMessage.Response.fail(in.readUTF()) : RpcMessage.Response.ok(readValue(in));
            }
            default: throw new IOException("未知类型标记 " + tag);
        }
    }

    /** varint：每字节 7 位数据，最高位 1 表示“后面还有”。9527 → b7 4a。 */
    static void varint(DataOutputStream out, long v) throws IOException {
        while ((v & ~0x7FL) != 0) { out.writeByte((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        out.writeByte((int) v);
    }

    static long readVarint(DataInputStream in) throws IOException {
        long v = 0; int shift = 0, b;
        do { b = in.readUnsignedByte(); v |= (long) (b & 0x7F) << shift; shift += 7; } while ((b & 0x80) != 0);
        return v;
    }
}
