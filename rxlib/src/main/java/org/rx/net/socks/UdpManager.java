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
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiFunc;

import java.net.InetAddress;
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

    static class WhitelistItem implements AutoCloseable {
        int refCnt;
        final Map<InetSocketAddress, Channel> channels = new ConcurrentHashMap<>();

        @Override
        public void close() {
            for (Channel ch : channels.values()) {
                ch.close();
            }
            channels.clear();
        }
    }

    static final Map<InetAddress, WhitelistItem> whitelist = new ConcurrentHashMap<>();

    public static synchronized void active(InetAddress srcAddr) {
        WhitelistItem item = whitelist.computeIfAbsent(srcAddr, k -> new WhitelistItem());
        item.refCnt++;
    }

    public static synchronized void inactive(InetAddress srcAddr) {
        WhitelistItem item = whitelist.get(srcAddr);
        item.refCnt--;
        if (item.refCnt == 0) {
            item.close();
        }
    }

    public static Channel openChannel(InetSocketAddress incomingEp, BiFunc<InetSocketAddress, Channel> loadFn) {
        WhitelistItem item = whitelist.get(incomingEp.getAddress());
        if (item == null) {
            throw new InvalidException("UDP security error, package from {}", incomingEp);
        }

        return item.channels.computeIfAbsent(incomingEp, loadFn);
    }

    public static void closeChannel(InetSocketAddress incomingEp) {
        WhitelistItem item = whitelist.get(incomingEp.getAddress());
        if (item == null) {
            log.warn("UDP security error, package from {}", incomingEp);
            return;
        }

        Channel channel = item.channels.remove(incomingEp);
        if (channel == null) {
            log.warn("UDP close fail {}", incomingEp);
            return;
        }
        tryClose(SocksContext.ctx(channel).upstream);
        channel.close();
    }

    public static void pendOrWritePacket(Channel outbound, Object packet) {
        SocksContext sc = SocksContext.ctx(outbound);
        ConcurrentLinkedQueue<Object> pending = sc.pendingPackages;
        if (pending != null && pending.add(packet)) {
            log.debug("PENDING_QUEUE {} => {} pend a packet", sc.source, sc.firstDestination);
            return;
        }
        outbound.writeAndFlush(packet);
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
