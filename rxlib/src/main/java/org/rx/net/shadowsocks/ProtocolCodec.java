package org.rx.net.shadowsocks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;
import org.rx.net.socks.UdpManager;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.util.List;

/**
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
            addr = UdpManager.socks5Decode(buf).socketAddress();
//            buf.skipBytes(3);
        }

        if (addr == null) {
            buf.retain();
        } else {
            ByteBuf addrBuf = ctx.alloc().directBuffer(64);
            UdpManager.encode(addrBuf, addr);

            buf = Unpooled.wrappedBuffer(addrBuf, buf.retain());
        }

        if (msg instanceof DatagramPacket) {
            DatagramPacket pack = (DatagramPacket) msg;
            if (!buf.equals(pack.content())) {
                msg = pack.replace(buf);
            }
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
            UnresolvedEndpoint addrRequest;
            try {
                addrRequest = UdpManager.decode(buf);
            } catch (Exception e) {
                log.warn("protocol error, fail to decode address request from {}, pls check client's cipher setting", inbound.remoteAddress(), e);
                if (!isUdp) {
                    inbound.close();
                }
                return;
            }
            //IDN.toUnicode(this.host);

            InetSocketAddress addr = addrRequest.socketAddress();
            inbound.attr(ShadowsocksConfig.REMOTE_DEST).set(addr);

            if (!isUdp) {
                tcpAddressed = true;
            }
        }
        buf.retain();
        out.add(msg);
    }
}
