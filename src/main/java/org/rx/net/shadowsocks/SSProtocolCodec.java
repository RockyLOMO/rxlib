package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.socks.SocksAddressType;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * https://www.shadowsocks.org/en/spec/Protocol.html
 * [1-byte type][variable-length host][2-byte port]
 * The following address types are defined:
 * <p>
 * 0x01: host is a 4-byte IPv4 address.
 * 0x03: host is a variable length string, starting with a 1-byte length, followed by up to 255-byte domain name.
 * 0x04: host is a 16-byte IPv6 address.
 * The port number is a 2-byte big-endian unsigned integer.
 **/
@Slf4j
public class SSProtocolCodec extends MessageToMessageCodec<Object, Object> {
    private boolean isClient;
    private boolean tcpAddressed;

    public SSProtocolCodec() {
        this(false);
    }

    public SSProtocolCodec(boolean isClient) {
        this.isClient = isClient;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf buf = Sockets.getMessageBuf(msg);

        //组装ss协议
        //udp [target address][payload]
        //tcp only [payload]
        boolean isUdp = ctx.channel().attr(SSCommon.IS_UDP).get();

        InetSocketAddress addr = null;
        if (isUdp) {
            addr = ctx.channel().attr(!isClient ? SSCommon.REMOTE_SRC : SSCommon.REMOTE_DEST).get();
        } else if (isClient && !tcpAddressed) {
            addr = ctx.channel().attr(SSCommon.REMOTE_DEST).get();
            tcpAddressed = true;
        }

        if (addr == null) {
            buf.retain();
        } else {
            SSAddressRequest addrRequest;
            if (addr.getAddress() instanceof Inet6Address) {
                addrRequest = new SSAddressRequest(SocksAddressType.IPv6, addr.getHostString(), addr.getPort());
            } else if (addr.getAddress() instanceof Inet4Address) {
                addrRequest = new SSAddressRequest(SocksAddressType.IPv4, addr.getHostString(), addr.getPort());
            } else {
                addrRequest = new SSAddressRequest(SocksAddressType.DOMAIN, addr.getHostString(), addr.getPort());
            }
            ByteBuf addrBuff = Unpooled.buffer(128);
            addrRequest.encode(addrBuff);

            buf = Unpooled.wrappedBuffer(addrBuff, buf.retain());
        }

        if (msg instanceof DatagramPacket) {
            msg = ((DatagramPacket) msg).replace(buf);
        } else {
            msg = buf;
        }
        out.add(msg);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf buf = Sockets.getMessageBuf(msg);

        if (buf.readableBytes() < 1 + 1 + 2) {// [1-byte type][variable-length host][2-byte port]
            return;
        }

        boolean isUdp = ctx.channel().attr(SSCommon.IS_UDP).get();

        if (isUdp || (!isClient && !tcpAddressed)) {
            SSAddressRequest addrRequest = SSAddressRequest.decode(buf);
            if (addrRequest == null) {
                log.warn("fail to decode address request from {}, pls check client's cipher setting", ctx.channel().remoteAddress());
                if (!ctx.channel().attr(SSCommon.IS_UDP).get()) {
                    ctx.close();
                }
                return;
            }
            log.debug(ctx.channel().id().toString() + " addressType = " + addrRequest.addressType() + ",host = " + addrRequest.host() + ",port = " + addrRequest.port() + ",dataBuff = " + buf.readableBytes());

            InetSocketAddress addr = new InetSocketAddress(addrRequest.host(), addrRequest.port());
            ctx.channel().attr(!isClient ? SSCommon.REMOTE_DEST : SSCommon.REMOTE_SRC).set(addr);

            if (!isUdp) {
                tcpAddressed = true;
            }
        }
        buf.retain();
        out.add(msg);
    }
}
