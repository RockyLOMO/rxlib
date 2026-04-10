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

    /** Active SOCKS5 client (TCP control connection) for the current relay session. */
    private Socks5Client activeClient;
    /** Current UDP_ASSOCIATE session. Non-null once a session has been established. */
    private Socks5UdpSession activeSession;
    /**
     * The actual UDP relay address returned by the remote SOCKS5 server after UDP_ASSOCIATE.
     * Exposed so the routing layer can forward datagrams to the correct relay port.
     */
    @Getter
    private InetSocketAddress udpRelayAddress;

    public SocksUdpUpstream(UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super(dstEp, config);
        this.next = next;
    }

    /**
     * Returns the effective SOCKS5 relay endpoint to use for sending UDP datagrams.
     * Falls back to the remote server's TCP address before a relay session is established.
     */
    public AuthenticEndpoint getSvrEp() {
        AuthenticEndpoint ep = next.getEndpoint();
        if (udpRelayAddress != null) {
            return new AuthenticEndpoint(udpRelayAddress, ep.getUsername(), ep.getPassword());
        }
        return ep;
    }

    /**
     * Lazily establishes a {@link Socks5UdpSession} with the remote SOCKS5 server on the
     * first packet, then caches the session on this instance for subsequent packets.
     *
     * <p>Once the session is established, {@link #udpRelayAddress} is updated to point at
     * the remote server's UDP relay address (from the UDP_ASSOCIATE response), so the
     * relay handler sends SOCKS5-encoded datagrams to the correct port.
     */
    @Override
    public void initChannel(Channel channel) {
        // Fast path: reuse existing session
        if (activeSession != null && !activeSession.isClosed()) {
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

            // Update instance state
            tryClose(activeSession);
            tryClose(activeClient);
            activeClient = client;
            activeSession = session;
            udpRelayAddress = relayAddr;
            log.debug("UDP upstream session established: {} -> relay={}", svrEp, relayAddr);

            // Tear down session when relay channel closes (RFC 1928: closing TCP control = end relay)
            channel.closeFuture().addListener(f -> {
                log.debug("Relay channel closed, closing UDP upstream session to {}", svrEp);
                tryClose(activeSession);
                tryClose(activeClient);
                activeSession = null;
                activeClient = null;
                udpRelayAddress = null;
            });
        } catch (Exception e) {
            log.error("Failed to establish UDP upstream session with {}", svrEp, e);
            tryClose(client);
        }
    }
}
