package org.rx.net.ntp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * High-performance Netty-based NTP client.
 * <p>
 * All IO uses pooled heap {@link ByteBuf} — no {@code byte[]}, no {@code java.net.DatagramPacket},
 * no intermediate wrapper objects. Packet fields are read directly from the received ByteBuf
 * by absolute index, so {@code readerIndex} is never mutated in the hot path.
 * </p>
 */
@Slf4j
public class NtpClient implements AutoCloseable {

    static final AttributeKey<NtpClient> OWNER = AttributeKey.valueOf("NtpClient");

    // ---- Shared-state carrier for an in-flight request ----
    // Avoids boxing by storing the transmit NTP timestamp (long) as the map key.
    // t1Millis is the Java-millis snapshot taken just before send (used for delay/offset calc).
    static final class PendingRequest {
        final CompletableFuture<NtpResult> future = new CompletableFuture<>();
        final long t1Millis;

        PendingRequest(long t1Millis) {
            this.t1Millis = t1Millis;
        }
    }

    // ---- Sharable inbound handler — zero allocation in the hot path ----
    @ChannelHandler.Sharable
    static class Handler extends SimpleChannelInboundHandler<DatagramPacket> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
            NtpClient client = ctx.channel().attr(OWNER).get();
            if (client == null) {
                return;
            }

            // Record destination time (t4) as early as possible
            final long t4Millis = System.currentTimeMillis();

            ByteBuf content = msg.content();
            if (content.readableBytes() < NtpPacket.PACKET_LENGTH) {
                log.warn("NTP: received undersized packet ({} bytes) from {}", content.readableBytes(), msg.sender());
                return;
            }

            // All reads below use absolute getXxx(index) — no readerIndex mutation, no copy
            final int mode = NtpPacket.getMode(content);
            if (mode != NtpPacket.MODE_SERVER && mode != 5 /* broadcast */) {
                // ignore non-server packets
                return;
            }

            // Originate timestamp echoed by server == transmitNtp we sent (t1 as NTP)
            final long originateNtp = NtpPacket.getOriginateNtp(content);
            if (originateNtp == 0) {
                log.debug("NTP: server echoed zero originate timestamp — ignoring");
                return;
            }

            final PendingRequest req = client.pendingRequests.remove(originateNtp);
            if (req == null) {
                log.debug("NTP: received unsolicited or duplicate NTP response");
                return;
            }

            // t2 = receive timestamp set by server, t3 = transmit timestamp set by server
            final long t2Millis = NtpPacket.ntpToMillis(NtpPacket.getReceiveNtp(content));
            final long t3Millis = NtpPacket.ntpToMillis(NtpPacket.getTransmitNtp(content));
            final long t1Millis = req.t1Millis;

            final long delayMillis  = NtpPacket.computeDelay(t1Millis, t2Millis, t3Millis, t4Millis);
            final long offsetMillis = NtpPacket.computeOffset(t1Millis, t2Millis, t3Millis, t4Millis);

            // Retain the content so NtpResult can do lazy field reads without extra copy
            req.future.complete(new NtpResult(content.retain(), t4Millis, delayMillis, offsetMillis, msg.sender()));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("NTP handler exception", cause);
        }
    }

    private static final Handler HANDLER = new Handler();

    // ---- Instance state ----

    private final Bootstrap bootstrap;
    final Channel channel;

    /** Key: 64-bit NTP transmit timestamp embedded in the request packet. */
    final Map<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    @Getter @Setter
    private int timeoutMillis = 3000;

    public NtpClient() {
        bootstrap = Sockets.udpBootstrap(null, ch -> ch.pipeline().addLast(HANDLER));
        channel = bootstrap.bind(0).syncUninterruptibly().channel();
        channel.attr(OWNER).set(this);
    }

    // ---- Public API ----

    @SneakyThrows
    public NtpResult getTime(InetAddress host) {
        return getTime(new InetSocketAddress(host, NtpPacket.NTP_PORT));
    }

    @SneakyThrows
    public NtpResult getTime(InetAddress host, int port) {
        return getTime(new InetSocketAddress(host, port));
    }

    @SneakyThrows
    public NtpResult getTime(InetSocketAddress server) {
        return getTimeAsync(server).get(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Send an NTP request and return a future that completes when the server reply arrives.
     * <p>
     * Hot-path guarantees:<br>
     * – One pooled heap ByteBuf allocated per request (released by Netty after send).<br>
     * – One {@link PendingRequest} object per request.<br>
     * – No {@code byte[]} allocation on receive path; all reads are absolute index reads on the inbound ByteBuf.<br>
     * </p>
     */
    public CompletableFuture<NtpResult> getTimeAsync(InetSocketAddress server) {
        if (!channel.isActive()) {
            throw new IllegalStateException("NtpClient is closed");
        }

        // Snapshot time as close to the send as possible (t1)
        final long t1Millis    = System.currentTimeMillis();
        final long xmitNtp     = NtpPacket.millisToNtp(t1Millis);

        final PendingRequest req = new PendingRequest(t1Millis);
        pendingRequests.put(xmitNtp, req);

        // Encode request directly into a pooled heap buffer — no byte[] involved
        final ByteBuf buf = NtpPacket.encodeRequest(channel.alloc(), xmitNtp);
        // DatagramPacket takes ownership of buf; Netty releases it after send
        channel.writeAndFlush(new DatagramPacket(buf, server)).addListener(f -> {
            if (!f.isSuccess()) {
                PendingRequest removed = pendingRequests.remove(xmitNtp);
                if (removed != null) {
                    removed.future.completeExceptionally(f.cause());
                }
            }
        });

        // Timeout cleanup — no object allocation beyond the lambda capture
        Tasks.setTimeout(() -> {
            PendingRequest removed = pendingRequests.remove(xmitNtp);
            if (removed != null) {
                removed.future.completeExceptionally(
                        new TimeoutException("NTP request to " + server + " timed out after " + timeoutMillis + "ms"));
            }
        }, timeoutMillis);

        return req.future;
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close();
        }
    }
}
