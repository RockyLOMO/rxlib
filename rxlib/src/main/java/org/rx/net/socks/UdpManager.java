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
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.tryClose;

@Slf4j
public final class UdpManager {
    static final Map<InetSocketAddress, ChannelFuture> channels = new ConcurrentHashMap<>();

    public static ChannelFuture open(InetSocketAddress srcEp, BiFunc<InetSocketAddress, ChannelFuture> loadFn) {
        return channels.computeIfAbsent(srcEp, loadFn);
    }

    public static void close(InetSocketAddress srcEp) {
        ChannelFuture chf = channels.remove(srcEp);
        if (chf == null) {
            log.warn("UDP error close fail {}", srcEp);
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
