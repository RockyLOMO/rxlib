package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.Tasks;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksConnectionTagRegistry;
import org.rx.net.socks.Socks5ClientHandler;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.TcpWarmPoolKey;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SocksTcpUpstream extends Upstream {
    private static final long HASH_OFFSET = 0xcbf29ce484222325L;
    private static final long HASH_PRIME = 0x100000001b3L;
    private static final AttributeKey<UpstreamSupport> ATTR_ACTIVE_SUPPORT =
            AttributeKey.valueOf("socksTcpUpstreamActiveSupport");

    private UpstreamSupport next;
    private boolean destinationPrepared;

    public SocksTcpUpstream(UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super(dstEp, config);
        this.next = next;
    }

    public void reuse(UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull UpstreamSupport next) {
        super.reuse(dstEp, config);
        this.next = next;
    }

    @Override
    public void initChannel(Channel channel) {
        prepareDestination();
        initTransport(channel);
        initProxyHandler(channel);
    }

    public AuthenticEndpoint getServerEndpoint() {
        return next.getEndpoint();
    }

    public TcpWarmPoolKey warmPoolKey() {
        return TcpWarmPoolKey.from(next.getEndpoint(), config, config.getReactorName());
    }

    public UnresolvedEndpoint prepareDestination() {
        if (destinationPrepared) {
            return destination;
        }
        destinationPrepared = true;
        SocksRpcContract facade = next.getFacade();
        if (facade == null
                || (!SocksRpcContract.FAKE_IPS.contains(destination.getHost()) && !SocksRpcContract.FAKE_PORTS.contains(destination.getPort())
                && Sockets.isValidIp(destination.getHost()))) {
            return destination;
        }

        UnresolvedEndpoint realDestination = destination;
        long hash = fakeEndpointHash(next, realDestination);
        destination = new UnresolvedEndpoint(SocksRpcContract.fakeHost(hash), Arrays.randomNext(SocksRpcContract.FAKE_PORT_OBFS));

        Cache<Long, Boolean> cache = Cache.getInstance();
        Long cacheKey = Long.valueOf(hash);
        if (!cache.containsKey(cacheKey)) {
            try {
                String dstEpStr = realDestination.toString();
                Tasks.runAsync(() -> {
                    facade.fakeEndpoint(hash, dstEpStr, SocksRpcContract.rpcToken());
                    return true;
                }).whenCompleteAsync((r, e) -> {
                    if (BooleanUtils.isTrue(r)) {
                        cache.put(cacheKey, r, CachePolicy.absolute(SocksRpcContract.FAKE_EXPIRE_SECONDS));
                    }
                }).get(SocksRpcContract.ASYNC_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.error("do fake", e);
            }
        }
        return destination;
    }

    static long fakeEndpointHash(UpstreamSupport support, UnresolvedEndpoint dstEp) {
        long hash = mixEndpoint(HASH_OFFSET, support == null ? null : support.getEndpoint());
        hash = mixString(hash, dstEp == null ? null : dstEp.getHost());
        hash = mixInt(hash, dstEp == null ? 0 : dstEp.getPort());
        return hash;
    }

    private static long mixEndpoint(long hash, AuthenticEndpoint endpoint) {
        InetSocketAddress inetEndpoint = endpoint == null ? null : endpoint.getInetEndpoint();
        if (inetEndpoint == null) {
            SocketAddress address = endpoint == null ? null : endpoint.getEndpoint();
            return mixInt(hash, address == null ? 0 : address.hashCode());
        }
        InetAddress address = inetEndpoint.getAddress();
        hash = address == null ? mixString(hash, inetEndpoint.getHostString()) : mixInt(hash, address.hashCode());
        return mixInt(hash, inetEndpoint.getPort());
    }

    private static long mixString(long hash, String value) {
        if (value == null || value.length() == 0) {
            return mixInt(hash, 0);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            hash ^= c & 0xFF;
            hash *= HASH_PRIME;
            hash ^= c >>> 8;
            hash *= HASH_PRIME;
        }
        return hash;
    }

    private static long mixInt(long hash, int value) {
        hash ^= value & 0xFF;
        hash *= HASH_PRIME;
        hash ^= (value >>> 8) & 0xFF;
        hash *= HASH_PRIME;
        hash ^= (value >>> 16) & 0xFF;
        hash *= HASH_PRIME;
        hash ^= (value >>> 24) & 0xFF;
        return hash * HASH_PRIME;
    }

    public void initTransport(Channel channel) {
        bindActiveConnection(channel);
        Sockets.addTcpClientHandler(channel, config, next.getEndpoint().getInetEndpoint());
        String trafficUser = next.getEndpoint().getParameters().get(SocksConnectionTagRegistry.PARAM_NAME);
        if (trafficUser != null) {
            // 内部无鉴权链路通过连接本地地址回绑统计用户，不走热路径。
            SocksConnectionTagRegistry.bindOnActive(channel, trafficUser);
        }
    }

    public void initProxyHandler(Channel channel) {
        AuthenticEndpoint svrEp = next.getEndpoint();
        Socks5ClientHandler proxyHandler = new Socks5ClientHandler(svrEp.getConnectEndpoint(), svrEp.getUsername(), svrEp.getPassword());
        proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
        channel.pipeline().addLast(proxyHandler);
    }

    public void bindActiveConnection(Channel channel) {
        if (channel == null || next == null) {
            return;
        }
        if (channel.attr(ATTR_ACTIVE_SUPPORT).setIfAbsent(next) != null) {
            return;
        }
        next.retainConnection();
        channel.closeFuture().addListener(f -> next.releaseConnection());
    }

    @Override
    public SocketAddress connectAddressHint() {
        return next.getEndpoint().getConnectEndpoint();
    }
}
