package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import lombok.extern.slf4j.Slf4j;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.io.Bytes;

@Slf4j
@ChannelHandler.Sharable
final class Udp2rawServerEntryHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private final Udp2rawServerEntryManager manager;

    Udp2rawServerEntryHandler(Udp2rawServerEntryManager manager) {
        this.manager = manager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket in) {
        ByteBuf content = in.content();
        Udp2rawFrame frame;
        try {
            frame = Udp2rawCodec.decode(content);
        } catch (Throwable e) {
            recordDrop("bad-frame");
            if (manager.server.getConfig().isDebug()) {
                log.warn("udp2raw discard bad frame from {}", in.sender(), e);
            }
            return;
        }
        if (frame.getType() != Udp2rawFrameType.DATA) {
            handleControlFrame(frame);
            return;
        }

        Udp2rawTunnelContext tunnel = manager.context(frame);
        if (tunnel == null) {
            recordDrop("unknown-tunnel");
            return;
        }

        Udp2rawSessionKey key = frame.sessionKey();
        Udp2rawSession session = tunnel.session(key);
        boolean newSession = session == null;
        if (Udp2rawAuthenticator.requiresAuth(tunnel.authMode, newSession, frame.getFlags())
                && !Udp2rawAuthenticator.verify(tunnel.sessionSecret, frame, content.slice())) {
            recordDrop("auth-fail");
            return;
        }
        if (newSession) {
            session = tunnel.getOrCreateSession(key, in.sender(), frame.getClientSource(),
                    frame.getDestination(), ctx.channel());
            if (session == null) {
                recordDrop("bad-session");
                return;
            }
        }
        if (!session.acceptRequestSeq(frame.getPacketSeq())) {
            recordDrop("duplicate");
            DiagnosticMetrics.record("socks.udp2raw.redundant.duplicate.drop.count", 1D, "direction=request");
            return;
        }

        session.updatePeer(ctx.channel(), in.sender());
        ByteBuf payload = content.slice();
        ByteBuf decoded = null;
        try {
            if (frame.hasFlag(Udp2rawCodec.FLAG_COMPRESSED)) {
                decoded = Udp2rawPayloadSupport.decompress(ctx.alloc(), payload, "request");
                if (decoded == null) {
                    recordDrop("decompress-fail");
                    return;
                }
                payload = decoded;
            }
            session.writeToDestination(payload);
        } finally {
            Bytes.release(decoded);
        }
    }

    private void handleControlFrame(Udp2rawFrame frame) {
        if (frame.getType() == Udp2rawFrameType.CLOSE) {
            Udp2rawTunnelContext tunnel = manager.context(frame);
            if (tunnel != null) {
                Udp2rawSession session = tunnel.session(frame.sessionKey());
                if (session != null) {
                    session.close("peer-close");
                }
            }
        }
    }

    private static void recordDrop(String reason) {
        DiagnosticMetrics.record("socks.udp2raw.drop.count", 1D, "reason=" + reason);
    }
}
