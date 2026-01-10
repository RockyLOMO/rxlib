package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.socks.SSAddressRequest;
import io.netty.handler.codec.socks.SocksAddressType;
import lombok.extern.slf4j.Slf4j;
import org.rx.io.Bytes;
import org.rx.net.Sockets;
import org.rx.net.socks.UdpManager;

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
public class ProtocolCodec extends MessageToMessageCodec<Object, Object> {
    private boolean tcpAddressed;

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf buf = Sockets.getMessageBuf(msg);

        //组装ss协议
        //udp [target address][payload]
        //tcp only [payload]
        Channel inbound = ctx.channel();
        boolean isUdp = inbound instanceof DatagramChannel;

        InetSocketAddress addr = null;
        if (isUdp) {
            addr = inbound.attr(SSCommon.REMOTE_SRC).get();
        }

        if (addr == null) {
            buf.retain();
        } else {
//            SSAddressRequest addrRequest;
//            if (addr.getAddress() instanceof Inet6Address) {
//                addrRequest = new SSAddressRequest(SocksAddressType.IPv6, addr.getHostString(), addr.getPort());
//            } else if (addr.getAddress() instanceof Inet4Address) {
//                addrRequest = new SSAddressRequest(SocksAddressType.IPv4, addr.getHostString(), addr.getPort());
//            } else {
//                addrRequest = new SSAddressRequest(SocksAddressType.DOMAIN, addr.getHostString(), addr.getPort());
//            }
            ByteBuf addrBuff = ctx.alloc().directBuffer(64);
//            addrRequest.encode(addrBuff);
            UdpManager.encode(addrBuff, addr);

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

        Channel inbound = ctx.channel();
        boolean isUdp = inbound instanceof DatagramChannel;

        if (isUdp || !tcpAddressed) {
            SSAddressRequest addrRequest = SSAddressRequest.decode(buf);
            if (addrRequest == null) {
                log.warn("fail to decode address request from {}, pls check client's cipher setting", inbound.remoteAddress());
                if (!isUdp) {
                    ctx.close();
                }
                return;
            }

            InetSocketAddress addr = new InetSocketAddress(addrRequest.host(), addrRequest.port());
            inbound.attr(SSCommon.REMOTE_DEST).set(addr);

            if (!isUdp) {
                tcpAddressed = true;
            }
        }
        buf.retain();
        out.add(msg);
    }
}
