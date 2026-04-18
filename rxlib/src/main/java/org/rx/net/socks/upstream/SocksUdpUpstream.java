package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Tasks;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.Socks5Client;
import org.rx.net.socks.Socks5Client.Socks5UdpLease;
import org.rx.net.socks.Socks5Client.Socks5UdpSession;
import org.rx.net.socks.Socks5UpstreamPoolManager;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.UdpRelayAttributes;
import org.rx.net.socks.UdpLeasePoolKey;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class SocksUdpUpstream extends Upstream {
    private static final AttributeKey<SessionHolder> ATTR_UDP_SESSION =
            AttributeKey.valueOf("socksUdpUpstreamSession");

    private final UpstreamSupport next;

    public SocksUdpUpstream(UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super(dstEp, config);
        this.next = next;
    }

    public AuthenticEndpoint getServerEndpoint() {
        return next.getEndpoint();
    }

    public UdpLeasePoolKey poolKey() {
        return UdpLeasePoolKey.from(next.getEndpoint(), config, config.getReactorName());
    }

    public long resolveRelayIdleHintMillis() {
        Map<String, String> parameters = next.getEndpoint().getParameters();
        if (parameters == null) {
            return 0L;
        }
        String value = parameters.get("udpRelayIdleSeconds");
        if (value == null) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds <= 0 ? 0L : Math.max(0L, seconds * 1000L - 5000L);
        } catch (NumberFormatException e) {
            log.warn("invalid udpRelayIdleSeconds {} for {}", value, next.getEndpoint());
            return 0L;
        }
    }

    public InetSocketAddress getUdpRelayAddress(Channel channel) {
        SessionHolder holder = channel.attr(ATTR_UDP_SESSION).get();
        return holder != null ? holder.relayAddr : null;
    }

    @Override
    public void initChannel(Channel channel) {
        SessionHolder holder = channel.attr(ATTR_UDP_SESSION).get();
        if (holder != null && holder.isValid()) {
            return;
        }

        if (holder != null) {
            channel.attr(ATTR_UDP_SESSION).set(null);
        }

        SocksConfig socksConfig = (SocksConfig) config;
        SocksRpcContract facade = next.getFacade();
        Socks5UpstreamPoolManager poolManager = Socks5UpstreamPoolManager.INSTANCE;
        if (socksConfig.isUdpLeasePoolEnabled() && facade != null && !poolManager.isUdpBreakerOpen(poolKey())) {
            Socks5UpstreamPoolManager.UdpLeasePool pool = poolManager.udpPool(this);
            if (pool != null) {
                Socks5UdpLease lease = pool.borrow();
                if (lease != null) {
                    if (claimRelay(channel, lease, facade, poolManager, socksConfig)) {
                        bindHolder(channel, SessionHolder.pooled(pool, lease));
                        return;
                    }
                    tryClose(lease);
                }
            }
        }

        initSlowPath(channel);
    }

    private boolean claimRelay(Channel channel, Socks5UdpLease lease, SocksRpcContract facade,
                               Socks5UpstreamPoolManager poolManager, SocksConfig socksConfig) {
        try {
            InetSocketAddress clientAddr = resolveClaimClientAddress(channel, lease);
            boolean ok = facade.claimUdpRelay(lease.getRelayPort(), clientAddr);
            if (ok) {
                poolManager.onUdpRpcSuccess(poolKey());
                return true;
            }
            poolManager.onUdpRpcFailure(poolKey(), socksConfig, "claim", null);
        } catch (Exception e) {
            poolManager.onUdpRpcFailure(poolKey(), socksConfig, "claim", e);
        }
        return false;
    }

    static InetSocketAddress resolveClaimClientAddress(Channel channel, Socks5UdpLease lease) {
        InetSocketAddress udpLocalAddr = channel.localAddress() instanceof InetSocketAddress
                ? (InetSocketAddress) channel.localAddress() : null;
        if (udpLocalAddr == null) {
            return null;
        }

        if (udpLocalAddr.getAddress() != null && !udpLocalAddr.getAddress().isAnyLocalAddress()) {
            return udpLocalAddr;
        }

        InetSocketAddress tcpLocalAddr = lease != null && lease.getTcpControl() != null
                && lease.getTcpControl().localAddress() instanceof InetSocketAddress
                ? (InetSocketAddress) lease.getTcpControl().localAddress() : null;
        if (tcpLocalAddr != null && tcpLocalAddr.getAddress() != null
                && !tcpLocalAddr.getAddress().isAnyLocalAddress()) {
            return new InetSocketAddress(tcpLocalAddr.getAddress(), udpLocalAddr.getPort());
        }
        return udpLocalAddr;
    }

    private void initSlowPath(Channel channel) {
        AuthenticEndpoint svrEp = next.getEndpoint();
        Socks5Client client = new Socks5Client(svrEp, config);
        try {
            long timeout = config != null && config.getConnectTimeoutMillis() > 0
                    ? config.getConnectTimeoutMillis() : 10000L;
            Socks5UdpSession session = client.udpAssociateAsync(channel).get(timeout, TimeUnit.MILLISECONDS);
            bindHolder(channel, SessionHolder.session(client, session));
        } catch (Exception e) {
            log.error("Failed to establish UDP upstream session with {}", svrEp, e);
            tryClose(client);
        }
    }

    private void bindHolder(Channel channel, SessionHolder holder) {
        channel.attr(ATTR_UDP_SESSION).set(holder);
        UdpRelayAttributes.addRedundantPeer(channel, holder.relayAddr);
        final SessionHolder finalHolder = holder;
        channel.closeFuture().addListener(f -> {
            SessionHolder active = channel.attr(ATTR_UDP_SESSION).getAndSet(null);
            if (active != finalHolder) {
                return;
            }
            if (!active.pooled) {
                tryClose(active.session);
                tryClose(active.client);
                return;
            }

            SocksRpcContract facade = next.getFacade();
            if (facade == null) {
                tryClose(active.lease);
                return;
            }

            Tasks.runAsync(() -> {
                boolean ok = false;
                try {
                    ok = facade.resetUdpRelay(active.lease.getRelayPort());
                    if (ok) {
                        Socks5UpstreamPoolManager.INSTANCE.onUdpRpcSuccess(poolKey());
                    } else {
                        Socks5UpstreamPoolManager.INSTANCE.onUdpRpcFailure(poolKey(), (SocksConfig) config, "reset", null);
                    }
                } catch (Throwable e) {
                    Socks5UpstreamPoolManager.INSTANCE.onUdpRpcFailure(poolKey(), (SocksConfig) config, "reset", e);
                }

                Socks5UpstreamPoolManager.UdpLeasePool pool = active.pool;
                if (ok && pool != null && !pool.isClosed()) {
                    pool.recycle(active.lease);
                    return;
                }
                tryClose(active.lease);
            });
        });
    }

    static final class SessionHolder {
        final Socks5Client client;
        final Socks5UdpSession session;
        final Socks5UdpLease lease;
        final Socks5UpstreamPoolManager.UdpLeasePool pool;
        final InetSocketAddress relayAddr;
        final boolean pooled;

        static SessionHolder session(Socks5Client client, Socks5UdpSession session) {
            return new SessionHolder(client, session, null, null, session.getRelayAddress(), false);
        }

        static SessionHolder pooled(Socks5UpstreamPoolManager.UdpLeasePool pool, Socks5UdpLease lease) {
            return new SessionHolder(null, null, lease, pool, lease.getRelayAddress(), true);
        }

        SessionHolder(Socks5Client client, Socks5UdpSession session, Socks5UdpLease lease,
                      Socks5UpstreamPoolManager.UdpLeasePool pool, InetSocketAddress relayAddr, boolean pooled) {
            this.client = client;
            this.session = session;
            this.lease = lease;
            this.pool = pool;
            this.relayAddr = relayAddr;
            this.pooled = pooled;
        }

        boolean isValid() {
            if (!pooled) {
                return session != null && !session.isClosed();
            }
            return lease != null && !lease.isClosed() && lease.getTcpControl().isActive();
        }
    }
}
