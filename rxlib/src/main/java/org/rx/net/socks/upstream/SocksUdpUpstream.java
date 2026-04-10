package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.Socks5Client;
import org.rx.net.socks.Socks5Client.Socks5UdpSession;
import org.rx.net.socks.SocksConfig;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.tryClose;

/**
 * UDP upstream that chains through a remote SOCKS5 server via {@link Socks5Client} UDP_ASSOCIATE.
 *
 * <p>When the local relay handler receives a UDP packet destined for a remote target,
 * this upstream establishes (or reuses) a {@link Socks5UdpSession} with the remote SOCKS5
 * server.  The relay handler then sends the SOCKS5-encoded UDP datagram to the
 * session's relay address, and the remote server forwards it to the actual destination.
 *
 * <p>Session lifecycle is tied to the per-client relay channel: establishing on the first
 * packet and tearing down when the relay channel closes.
 *
 * <h3>Flow</h3>
 * <pre>
 *   App ──UDP──▶ [local relay] ──SOCKS5 UDP──▶ [remote SOCKS5 relay] ──UDP──▶ destination
 *   App ◀──UDP── [local relay] ◀──SOCKS5 UDP── [remote SOCKS5 relay] ◀──UDP── destination
 * </pre>
 */
@Slf4j
public class SocksUdpUpstream extends Upstream {

    private final UpstreamSupport next;

    private static final io.netty.util.AttributeKey<SessionHolder> ATTR_UDP_SESSION =
            io.netty.util.AttributeKey.valueOf("socksUdpUpstreamSession");

    public SocksUdpUpstream(UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super(dstEp, config);
        this.next = next;
    }

    public java.net.InetSocketAddress getUdpRelayAddress(Channel channel) {
        SessionHolder holder = channel.attr(ATTR_UDP_SESSION).get();
        return holder != null ? holder.relayAddr : null;
    }

    /**
     * Lazily establishes a {@link Socks5UdpSession} with the remote SOCKS5 server on the
     * first packet, then caches the session on the relay channel for subsequent packets.
     *
     * <p>Once the session is established, it is stored in the channel's attributes.
     */
    @Override
    public void initChannel(Channel channel) {
        // Fast path: reuse existing session cached on the relay channel
        SessionHolder holder = channel.attr(ATTR_UDP_SESSION).get();
        if (holder != null && !holder.session.isClosed()) {
            return;
        }

        // Slow path: establish new UDP_ASSOCIATE session with remote SOCKS5 server
        AuthenticEndpoint svrEp = next.getEndpoint();
        Socks5Client client = new Socks5Client(svrEp, config);
        try {
            long timeout = config != null && config.getConnectTimeoutMillis() > 0
                    ? config.getConnectTimeoutMillis() : 10000L;
            // Pass the relay channel directly so Session.udpRelay is never null.
            Socks5UdpSession session = client.udpAssociateAsync(channel)
                    .get(timeout, TimeUnit.MILLISECONDS);

            InetSocketAddress relayAddr = session.getRelayAddress();

            // Update channel state
            holder = new SessionHolder(client, session, relayAddr);
            channel.attr(ATTR_UDP_SESSION).set(holder);
            log.debug("UDP upstream session established: {} -> relay={}", svrEp, relayAddr);

            final SessionHolder finalHolder = holder;
            // Tear down session when relay channel closes (RFC 1928: closing TCP control = end relay)
            channel.closeFuture().addListener(f -> {
                log.debug("Relay channel closed, closing UDP upstream session to {}", svrEp);
                tryClose(finalHolder.session);
                tryClose(finalHolder.client);
                channel.attr(ATTR_UDP_SESSION).set(null);
            });
        } catch (Exception e) {
            log.error("Failed to establish UDP upstream session with {}", svrEp, e);
            tryClose(client);
        }
    }

    static final class SessionHolder {
        final Socks5Client client;
        final Socks5UdpSession session;
        final InetSocketAddress relayAddr;

        SessionHolder(Socks5Client client, Socks5UdpSession session, InetSocketAddress relayAddr) {
            this.client = client;
            this.session = session;
            this.relayAddr = relayAddr;
        }
    }
}
