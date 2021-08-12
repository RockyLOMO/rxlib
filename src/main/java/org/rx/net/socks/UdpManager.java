package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.exception.ApplicationException;
import org.rx.io.Bytes;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.tryClose;

@Slf4j
public final class UdpManager {
    @RequiredArgsConstructor
    @Getter
    public static class UdpChannelUpstream {
        private final Channel channel;
        private final Upstream upstream;
    }

    public static final ChannelFutureListener FLUSH_PENDING_QUEUE = f -> {
        Channel outbound = f.channel();
        InetSocketAddress srcEp = SocksContext.udpSource(outbound);
        if (!f.isSuccess()) {
            closeChannel(srcEp);
            return;
        }

        int size = SocksContext.flushPendingQueue(outbound);
        if (size > 0) {
            InetSocketAddress dstEp = SocksContext.udpDestination(outbound);
            log.info("PENDING_QUEUE {} => {} flush {} packets", srcEp, dstEp, size);
        }
    };
    static final Map<InetSocketAddress, UdpChannelUpstream> HOLD = new ConcurrentHashMap<>();

    public static void pendOrWritePacket(Channel outbound, Object packet) {
        if (SocksContext.addPendingPacket(outbound, packet)) {
            InetSocketAddress srcEp = SocksContext.udpSource(outbound);
            InetSocketAddress dstEp = SocksContext.udpDestination(outbound);
            log.info("PENDING_QUEUE {} => {} pend a packet", srcEp, dstEp);
            return;
        }
        outbound.writeAndFlush(packet);
    }

    public static UdpChannelUpstream openChannel(InetSocketAddress incomingEp, BiFunc<InetSocketAddress, UdpChannelUpstream> loadFn) {
        return HOLD.computeIfAbsent(incomingEp, k -> {
            try {
                return loadFn.invoke(k);
            } catch (Throwable e) {
                throw ApplicationException.sneaky(e);
            }
        });
    }

    public static void closeChannel(InetSocketAddress incomingEp) {
        UdpChannelUpstream ctx = HOLD.remove(incomingEp);
        if (ctx == null) {
            log.error("CloseChannel fail {} <> {}", incomingEp, HOLD.keySet());
            return;
        }
        tryClose(ctx.upstream);
        ctx.channel.close();
    }

    public static ByteBuf socks5Encode(ByteBuf inBuf, UnresolvedEndpoint dstEp) {
        ByteBuf outBuf = Bytes.directBuffer(64 + inBuf.readableBytes());
        outBuf.writeZero(3);
        UdpManager.encode(outBuf, dstEp);
        outBuf.writeBytes(inBuf);
        return outBuf;
    }

    @SneakyThrows
    public static void encode(ByteBuf buf, UnresolvedEndpoint ep) {
        Socks5AddressType addrType;
        if (NetUtil.isValidIpV4Address(ep.getHost())) {
            addrType = Socks5AddressType.IPv4;
        } else if (NetUtil.isValidIpV6Address(ep.getHost())) {
            addrType = Socks5AddressType.IPv6;
        } else {
            addrType = Socks5AddressType.DOMAIN;
        }
        buf.writeByte(addrType.byteValue());
        Socks5AddressEncoder.DEFAULT.encodeAddress(addrType, ep.getHost(), buf);
        buf.writeShort(ep.getPort());
    }

    @SneakyThrows
    public static UnresolvedEndpoint decode(ByteBuf buf) {
        Socks5AddressType addrType = Socks5AddressType.valueOf(buf.readByte());
        String dstAddr = Socks5AddressDecoder.DEFAULT.decodeAddress(addrType, buf);
        return new UnresolvedEndpoint(dstAddr, buf.readUnsignedShort());
    }
}
