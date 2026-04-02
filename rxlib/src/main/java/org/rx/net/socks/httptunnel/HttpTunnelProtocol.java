package org.rx.net.socks.httptunnel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import lombok.SneakyThrows;
import org.rx.net.support.UnresolvedEndpoint;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP 隧道二进制协议
 * <pre>
 * +--------+----------+------+----------+----------+-----------+
 * | ACTION | CONN_ID  | ATYP | DST.ADDR | DST.PORT |   DATA    |
 * +--------+----------+------+----------+----------+-----------+
 * |   1    |    4     |  1   | Variable |    2     | Variable  |
 * +--------+----------+------+----------+----------+-----------+
 * </pre>
 * CONNECT/UDP_FORWARD 包含完整的 ATYP+DST 字段
 * FORWARD/CLOSE 只包含 ACTION + CONN_ID + DATA(FORWARD) / 无DATA(CLOSE)
 */
public final class HttpTunnelProtocol {
    /**
     * TCP 建连
     */
    public static final byte ACTION_CONNECT = 1;
    /**
     * TCP 数据转发
     */
    public static final byte ACTION_FORWARD = 2;
    /**
     * 关闭连接
     */
    public static final byte ACTION_CLOSE = 3;
    /**
     * UDP 数据转发
     */
    public static final byte ACTION_UDP_FORWARD = 4;
    /**
     * 长轮询拉取数据
     */
    public static final byte ACTION_POLL = 5;

    private static final AtomicInteger CONN_ID_GEN = new AtomicInteger(0);

    public static int nextConnId() {
        return CONN_ID_GEN.incrementAndGet();
    }

    /**
     * 编码 CONNECT 请求: ACTION(1) + CONN_ID(4) + ATYP(1) + DST.ADDR(variable) + DST.PORT(2)
     */
    public static ByteBuf encodeConnect(int connId, UnresolvedEndpoint dst) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(64);
        buf.writeByte(ACTION_CONNECT);
        buf.writeInt(connId);
        encodeAddress(buf, dst);
        return buf;
    }

    /**
     * 编码 FORWARD 请求: ACTION(1) + CONN_ID(4) + DATA(variable)
     */
    public static ByteBuf encodeForward(int connId, byte[] data) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(5 + data.length);
        buf.writeByte(ACTION_FORWARD);
        buf.writeInt(connId);
        buf.writeBytes(data);
        return buf;
    }

    /**
     * 编码 CLOSE 请求: ACTION(1) + CONN_ID(4)
     */
    public static ByteBuf encodeClose(int connId) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(5);
        buf.writeByte(ACTION_CLOSE);
        buf.writeInt(connId);
        return buf;
    }

    /**
     * 编码 UDP_FORWARD 请求: ACTION(1) + CONN_ID(4) + ATYP(1) + DST.ADDR(variable) + DST.PORT(2) + DATA(variable)
     */
    public static ByteBuf encodeUdpForward(int connId, UnresolvedEndpoint dst, byte[] data) {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.heapBuffer(64 + data.length);
        buf.writeByte(ACTION_UDP_FORWARD);
        buf.writeInt(connId);
        encodeAddress(buf, dst);
        buf.writeBytes(data);
        return buf;
    }

    @SneakyThrows
    public static void encodeAddress(ByteBuf buf, UnresolvedEndpoint dst) {
        String host = dst.getHost();
        Socks5AddressType addrType;
        if (NetUtil.isValidIpV4Address(host)) {
            addrType = Socks5AddressType.IPv4;
        } else if (NetUtil.isValidIpV6Address(host)) {
            addrType = Socks5AddressType.IPv6;
        } else {
            addrType = Socks5AddressType.DOMAIN;
        }
        buf.writeByte(addrType.byteValue());
        Socks5AddressEncoder.DEFAULT.encodeAddress(addrType, host, buf);
        buf.writeShort(dst.getPort());
    }

    @SneakyThrows
    public static UnresolvedEndpoint decodeAddress(ByteBuf buf) {
        Socks5AddressType addrType = Socks5AddressType.valueOf(buf.readByte());
        String dstAddr = Socks5AddressDecoder.DEFAULT.decodeAddress(addrType, buf);
        return new UnresolvedEndpoint(dstAddr, buf.readUnsignedShort());
    }
}
