package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.socksx.v5.Socks5AddressDecoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressEncoder;
import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.rx.core.Extends.tryClose;

@Slf4j
public final class UdpManager {
    public static final ChannelFutureListener FLUSH_PENDING_QUEUE = f -> {
        Channel outbound = f.channel();
        SocksContext sc = SocksContext.ctx(outbound);
        if (!f.isSuccess()) {
//            sc.pendingPackages = null;
            closeChannel(sc.source);
            return;
        }

        ConcurrentLinkedQueue<Object> queue = sc.pendingPackages;
        if (queue == null) {
            return;
        }
        int size = queue.size();
        Sockets.writeAndFlush(outbound, queue);
        sc.pendingPackages = null;
        if (size > 0) {
            log.info("PENDING_QUEUE {} => {} flush {} packets", sc.source, sc.firstDestination, size);
        }
    };
    static final Map<InetSocketAddress, Channel> holder = new ConcurrentHashMap<>();

    public static void pendOrWritePacket(Channel outbound, Object packet) {
        SocksContext sc = SocksContext.ctx(outbound);
        ConcurrentLinkedQueue<Object> queue = sc.pendingPackages;
        if (queue != null && queue.add(packet)) {
            log.debug("PENDING_QUEUE {} => {} pend a packet", sc.source, sc.firstDestination);
            return;
        }
        outbound.writeAndFlush(packet);
    }

    public static Channel openChannel(InetSocketAddress incomingEp, BiFunc<InetSocketAddress, Channel> loadFn) {
        return holder.computeIfAbsent(incomingEp, loadFn);
    }

    public static void closeChannel(InetSocketAddress incomingEp) {
        Channel channel = holder.remove(incomingEp);
        if (channel == null) {
            log.error("CloseChannel fail {} <> {}", incomingEp, holder.keySet());
            return;
        }
        tryClose(SocksContext.ctx(channel).upstream);
        channel.close();
    }

    public static ByteBuf socks5Encode(ByteBuf buf, UnresolvedEndpoint dstEp) {
        ByteBuf outBuf = Bytes.directBuffer(64 + buf.readableBytes());
        outBuf.writeZero(3);
        encode(outBuf, dstEp);
        outBuf.writeBytes(buf);
        return outBuf;
    }

    public static UnresolvedEndpoint socks5Decode(ByteBuf buf) {
        buf.skipBytes(3);
        return decode(buf);
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
