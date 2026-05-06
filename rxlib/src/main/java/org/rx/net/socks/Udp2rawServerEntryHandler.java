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
import org.rx.net.Sockets;

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
        int datagramBytes = content.readableBytes();
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
            handleControlFrame(ctx, in, frame, content.slice(), datagramBytes);
            return;
        }

        Udp2rawTunnelContext tunnel = manager.context(frame);
        if (tunnel == null) {
            recordDrop("unknown-tunnel");
            return;
        }
        long now = System.currentTimeMillis();
        if (tunnel.isPeerBlocked(in.sender(), now)) {
            recordDrop("peer-blocked");
            return;
        }
        if (!tunnel.allowPeerPacket(in.sender(), now)) {
            recordDrop("peer-rate-limit");
            return;
        }

        Udp2rawSessionKey key = frame.sessionKey();
        Udp2rawSession session = tunnel.session(key);
        boolean newSession = session == null;
        boolean authOk = false;
        boolean authRequired = Udp2rawAuthenticator.requiresAuth(tunnel.authMode, newSession, frame.getFlags());
        if (authRequired) {
            authOk = Udp2rawAuthenticator.verify(tunnel.sessionSecret, frame, content.slice());
            if (!authOk) {
                tunnel.recordAuthFailure(in.sender(), now);
                recordDrop("auth-fail");
                return;
            }
        }
        boolean peerRebind = !newSession && !session.isPeer(in.sender());
        if (peerRebind && !authOk) {
            if (!frame.hasFlag(Udp2rawCodec.FLAG_AUTH_TAG)
                    || !Udp2rawAuthenticator.verify(tunnel.sessionSecret, frame, content.slice())) {
                if (frame.hasFlag(Udp2rawCodec.FLAG_AUTH_TAG)) {
                    tunnel.recordAuthFailure(in.sender(), now);
                }
                recordDrop("peer-rebind-auth-required");
                return;
            }
            authOk = true;
        }
        if (authOk) {
            tunnel.recordAuthSuccess(in.sender());
        }
        boolean redundantFrame = frame.hasFlag(Udp2rawCodec.FLAG_REDUNDANT);
        if (redundantFrame) {
            tunnel.recordRedundantReceived();
        }
        if (newSession) {
            session = tunnel.getOrCreateSession(key, in.sender(), frame.getClientSource(),
                    frame.getDestination(), ctx.channel());
            if (session == null) {
                recordDrop("bad-session");
                return;
            }
            if (!session.isPeer(in.sender()) && !authOk) {
                recordDrop("peer-rebind-auth-required");
                return;
            }
        }
        if (!session.acceptRequestSeq(frame.getPacketSeq())) {
            recordDrop("duplicate");
            DiagnosticMetrics.record("socks.udp2raw.redundant.duplicate.drop.count", 1D, "direction=request");
            return;
        }
        if (redundantFrame) {
            tunnel.recordRedundantUnique("request");
        }

        if (!session.updatePeer(ctx.channel(), in.sender(), authOk)) {
            recordDrop("peer-rebind-auth-required");
            return;
        }
        tunnel.noteMtuPeer(ctx.channel(), in.sender());
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

    private void handleControlFrame(ChannelHandlerContext ctx, DatagramPacket in,
            Udp2rawFrame frame, ByteBuf payload, int datagramBytes) {
        if (frame.getType() == Udp2rawFrameType.CLOSE) {
            Udp2rawTunnelContext tunnel = manager.context(frame);
            if (tunnel != null) {
                Udp2rawSession session = tunnel.session(frame.sessionKey());
                if (session != null) {
                    session.close("peer-close");
                }
            }
            return;
        }
        if (frame.getType() == Udp2rawFrameType.MTU_PROBE) {
            handleMtuProbe(ctx, in, frame, payload, datagramBytes);
            return;
        }
        if (frame.getType() == Udp2rawFrameType.MTU_ACK) {
            handleMtuAck(in, frame, payload);
        }
    }

    private void handleMtuProbe(ChannelHandlerContext ctx, DatagramPacket in,
            Udp2rawFrame frame, ByteBuf payload, int datagramBytes) {
        Udp2rawTunnelContext tunnel = manager.context(frame);
        if (tunnel == null) {
            recordDrop("unknown-tunnel");
            return;
        }
        long now = System.currentTimeMillis();
        if (tunnel.isPeerBlocked(in.sender(), now) || !tunnel.allowPeerPacket(in.sender(), now)) {
            recordDrop("peer-rate-limit");
            return;
        }
        if (!frame.hasFlag(Udp2rawCodec.FLAG_AUTH_TAG)
                || !Udp2rawAuthenticator.verify(tunnel.sessionSecret, frame, payload)) {
            tunnel.recordAuthFailure(in.sender(), now);
            recordDrop("mtu-probe-auth-fail");
            return;
        }
        tunnel.recordAuthSuccess(in.sender());
        tunnel.touch();
        tunnel.noteMtuPeer(ctx.channel(), in.sender());
        sendMtuAck(ctx, in, tunnel, frame.getPacketSeq(), datagramBytes);
    }

    private void sendMtuAck(ChannelHandlerContext ctx, DatagramPacket in,
            Udp2rawTunnelContext tunnel, long seq, int acceptedMtu) {
        ByteBuf encoded = null;
        try {
            encoded = Udp2rawMtuProbeSupport.encodeAck(ctx.alloc(), tunnel.sessionSecret,
                    tunnel.sessionHi, tunnel.sessionLo, seq, acceptedMtu);
            Sockets.UdpWriteResult result = Sockets.writeUdp(ctx.channel(),
                    new DatagramPacket(encoded, in.sender()), manager.server.getConfig(),
                    "socks.udp2raw", "flow=mtu-ack");
            encoded = null;
            if (result != Sockets.UdpWriteResult.ACCEPTED) {
                DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D,
                        "action=ack-drop,result=" + result);
            }
        } catch (Throwable e) {
            Bytes.release(encoded);
            DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D, "action=ack-encode-fail");
            log.warn("udp2raw MTU ack send failed to {}", in.sender(), e);
        }
    }

    private void handleMtuAck(DatagramPacket in, Udp2rawFrame frame, ByteBuf payload) {
        Udp2rawTunnelContext tunnel = manager.context(frame);
        if (tunnel == null) {
            recordDrop("unknown-tunnel");
            return;
        }
        long now = System.currentTimeMillis();
        if (tunnel.isPeerBlocked(in.sender(), now)) {
            recordDrop("peer-blocked");
            return;
        }
        if (!tunnel.allowPeerPacket(in.sender(), now)) {
            recordDrop("peer-rate-limit");
            return;
        }
        if (!frame.hasFlag(Udp2rawCodec.FLAG_AUTH_TAG)
                || !Udp2rawAuthenticator.verify(tunnel.sessionSecret, frame, payload)) {
            tunnel.recordAuthFailure(in.sender(), now);
            DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D, "action=bad-ack,side=server");
            return;
        }
        if (tunnel.mtuState == null) {
            DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D, "action=bad-ack,side=server");
            return;
        }
        int acceptedMtu = Udp2rawMtuProbeSupport.readAckAcceptedMtu(payload);
        if (acceptedMtu <= 0) {
            DiagnosticMetrics.record("socks.udp2raw.mtu.probe.count", 1D,
                    "action=bad-ack-payload,side=server");
            return;
        }
        if (tunnel.acceptMtuAck(in.sender(), frame.getPacketSeq(), acceptedMtu, now)) {
            tunnel.recordAuthSuccess(in.sender());
            tunnel.touch();
        }
    }

    private static void recordDrop(String reason) {
        DiagnosticMetrics.record("socks.udp2raw.drop.count", 1D, "reason=" + reason);
    }
}
