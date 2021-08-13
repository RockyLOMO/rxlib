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
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiFunc;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.App.tryClose;

@Slf4j
public final class UdpManager {
    public static final ChannelFutureListener FLUSH_PENDING_QUEUE = f -> {
        Channel outbound = f.channel();
        InetSocketAddress srcEp = SocksContext.realSource(outbound);
        if (!f.isSuccess()) {
            closeChannel(srcEp);
            return;
        }

//        sleep(1000);
//        System.out.println(outbound.isActive());
        int size = SocksContext.flushPendingQueue(outbound);
        if (size > 0) {
            UnresolvedEndpoint dstEp = SocksContext.realDestination(outbound);
            log.debug("PENDING_QUEUE {} => {} flush {} packets", srcEp, dstEp, size);
        }
    };
    static final Map<InetSocketAddress, Channel> HOLD = new ConcurrentHashMap<>();

    public static void pendOrWritePacket(Channel outbound, Object packet) {
        if (SocksContext.addPendingPacket(outbound, packet)) {
            InetSocketAddress srcEp = SocksContext.realSource(outbound);
            UnresolvedEndpoint dstEp = SocksContext.realDestination(outbound);
            log.debug("PENDING_QUEUE {} => {} pend a packet", srcEp, dstEp);
            return;
        }
        outbound.writeAndFlush(packet).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    public static Channel openChannel(InetSocketAddress incomingEp, BiFunc<InetSocketAddress, Channel> loadFn) {
        return HOLD.computeIfAbsent(incomingEp, loadFn.toFunction());
    }

    public static void closeChannel(InetSocketAddress incomingEp) {
        Channel channel = HOLD.remove(incomingEp);
        if (channel == null) {
            log.error("CloseChannel fail {} <> {}", incomingEp, HOLD.keySet());
            return;
        }
        tryClose(SocksContext.upstream(channel));
        channel.close();
    }

    public static ByteBuf socks5Encode(ByteBuf inBuf, UnresolvedEndpoint dstEp) {
        ByteBuf outBuf = Bytes.directBuffer(64 + inBuf.readableBytes());
        outBuf.writeZero(3);
        encode(outBuf, dstEp);
        outBuf.writeBytes(inBuf);
        return outBuf;
    }

    public static UnresolvedEndpoint socks5Decode(ByteBuf inBuf) {
        inBuf.skipBytes(3);
        return decode(inBuf);
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
