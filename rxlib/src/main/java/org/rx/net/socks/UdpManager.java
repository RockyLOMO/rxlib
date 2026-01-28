package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.SocketConfig;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.tryClose;

@Slf4j
public final class UdpManager {
//    public static final byte socksRegion = 0;
//    public static final byte udp2rawRegion = 1;
//    public static final byte ssRegion = 2;

//    static long packKey(byte region, InetSocketAddress addr) {
//        InetAddress inetAddr = addr.getAddress();
//
//        // 1. 防御性检查：如果是 IPv6，long 存不下，需要抛出异常或使用其他方案
//        byte[] bytes = inetAddr.getAddress();
//        if (bytes.length > 4) {
//            throw new IllegalArgumentException("Only IPv4 addresses are supported for long packing");
//        }
//
//        // 2. 将 IP 字节转为无符号 int (处理符号位)
//        long ipUnsigned = ((bytes[0] & 0xFFL) << 24) |
//                ((bytes[1] & 0xFFL) << 16) |
//                ((bytes[2] & 0xFFL) << 8) |
//                (bytes[3] & 0xFFL);
//
//        long port = addr.getPort() & 0xFFFFL; // 确保端口也是无符号的
//        long reg = region & 0xFFL;            // 确保 region 不会进行符号扩展
//
//        // 分布: [Region(8位)] [Port(16位)] [IP(32位)]
//        // 总计 56 位，剩下的 8 位（最高位）为空
//        return (reg << 48) | (port << 32) | ipUnsigned;
//    }
//
//    @SneakyThrows
//    public static InetSocketAddress unpackToAddress(long key) {
//        int ipInt = (int) (key & 0xFFFFFFFFL);
//        int port = (int) ((key >>> 32) & 0xFFFFL);
//
//        // 将 int 转回 byte[] 数组
//        byte[] ipBytes = new byte[]{
//                (byte) (ipInt >>> 24),
//                (byte) (ipInt >>> 16),
//                (byte) (ipInt >>> 8),
//                (byte) ipInt
//        };
//
//        // 提取 Region (高 8 位：从第 48 位开始)

    /// /        byte region = (byte) ((key >>> 48) & 0xFFL);
//        return new InetSocketAddress(InetAddress.getByAddress(ipBytes), port);
//    }

    public static final byte socksRegion = 0;
    public static final byte udp2rawRegion = 1;
    public static final byte ssRegion = 2;

    @Getter
    @RequiredArgsConstructor
    public static class ChannelKey {
        final byte region;
        final InetSocketAddress source;
        final SocketConfig config;

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            ChannelKey that = (ChannelKey) o;
            return region == that.region && Objects.equals(source, that.source) && Objects.equals(config, that.config);
        }

        @Override
        public int hashCode() {
            return Objects.hash(region, source, config);
        }
    }

    static final ConcurrentHashMap<ChannelKey, ChannelFuture> channels = new ConcurrentHashMap<>();

    public static ChannelFuture open(byte region, InetSocketAddress srcEp, SocketConfig config,
                                     BiFunc<ChannelKey, ChannelFuture> bindFn) {
        ChannelKey key = new ChannelKey(region, srcEp, config);
        return channels.computeIfAbsent(key, bindFn);
    }

    public static void close(ChannelKey ck) {
        ChannelFuture chf = channels.remove(ck);
        if (chf == null) {
            log.warn("UDP error close fail {}", ck);
            return;
        }
        Channel ch = chf.channel();
        tryClose(SocksContext.ctx(ch).upstream);
        ch.close();
    }

    public static CompositeByteBuf socks5Encode(ByteBuf buf, UnresolvedEndpoint dst) {
        return socks5Encode(buf, dst.getHost(), dst.getPort());
    }

    public static CompositeByteBuf socks5Encode(ByteBuf buf, InetSocketAddress dst) {
        return socks5Encode(buf, dst.getHostString(), dst.getPort());
    }

    public static CompositeByteBuf socks5Encode(ByteBuf buf, String host, int port) {
        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        ByteBuf header = allocator.directBuffer(64);
        CompositeByteBuf compositeBuf = allocator.compositeDirectBuffer(2);
        try {
            header.writeZero(3);
            encode(header, host, port);
            compositeBuf.addComponents(true, header, buf);
            return compositeBuf;
        } catch (Exception e) {
            header.release();
            compositeBuf.release();
            throw e;
        }
    }

    public static UnresolvedEndpoint socks5Decode(ByteBuf buf) {
        buf.skipBytes(3);
        return decode(buf);
    }

    public static void encode(ByteBuf buf, UnresolvedEndpoint dst) {
        encode(buf, dst.getHost(), dst.getPort());
    }

    public static void encode(ByteBuf buf, InetSocketAddress dst) {
        encode(buf, dst.getHostString(), dst.getPort());
    }

    @SneakyThrows
    public static void encode(ByteBuf buf, String host, int port) {
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
        buf.writeShort(port);
    }

    @SneakyThrows
    public static UnresolvedEndpoint decode(ByteBuf buf) {
        Socks5AddressType addrType = Socks5AddressType.valueOf(buf.readByte());
        String dstAddr = Socks5AddressDecoder.DEFAULT.decodeAddress(addrType, buf);
        return new UnresolvedEndpoint(dstAddr, buf.readUnsignedShort());
    }
}
