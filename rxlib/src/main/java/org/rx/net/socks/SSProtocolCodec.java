package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import org.rx.net.Sockets;

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
public class SSProtocolCodec extends MessageToMessageCodec<Object, Object> {
    private boolean tcpAddressed;
    private ByteBuf prefix;

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        ByteBuf buf = Sockets.getMessageBuf(msg);

        //组装ss协议
        buf.retain();

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
        Channel inbound = ctx.channel();
        boolean isUdp = inbound instanceof DatagramChannel;

        if (isUdp || !tcpAddressed) {
            ByteBuf header = buf;
            try {
                if (!isUdp) {
                    int length = UdpManager.addressHeaderLength(buf, buf.readerIndex(), buf.readableBytes());
                    if (prefix != null || length == 0 || length > buf.readableBytes()) {
                        if (!buf.isReadable()) return;
                        if (prefix == null) prefix = ctx.alloc().buffer(19, 259);
                        while (true) {
                            length = UdpManager.addressHeaderLength(prefix, 0, prefix.readableBytes());
                            if (length < 0) throw new IllegalArgumentException("invalid SS address type");
                            if (length > 0 && prefix.readableBytes() == length) break;
                            if (!buf.isReadable()) return;
                            int needed = length == 0 ? 1 : length - prefix.readableBytes();
                            prefix.writeBytes(buf, Math.min(needed, buf.readableBytes()));
                        }
                        header = prefix;
                    }
                }
                inbound.attr(ShadowsocksConfig.REMOTE_DEST).set(UdpManager.decode(header));
                releasePrefix();
            } catch (IllegalArgumentException e) {
                releasePrefix();
                if (!isUdp) inbound.close();
                return;
            }
            if (!isUdp) {
                tcpAddressed = true;
            }
        }
        buf.retain();
        out.add(msg);
    }

    private void releasePrefix() {
        if (prefix != null) {
            prefix.release();
            prefix = null;
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        releasePrefix();
        super.handlerRemoved(ctx);
    }
}
