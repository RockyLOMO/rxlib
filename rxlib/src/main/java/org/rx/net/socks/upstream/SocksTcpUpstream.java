package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.codec.CodecUtil;
import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.core.CachePolicy;
import org.rx.core.Tasks;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.Socks5ClientHandler;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.TcpWarmPoolKey;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SocksTcpUpstream extends Upstream {
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

        String dstEpStr = destination.toString();
        BigInteger hash = CodecUtil.hashUnsigned64(dstEpStr.getBytes(StandardCharsets.UTF_8));
        destination = new UnresolvedEndpoint(String.format("%s%s", hash, SocksRpcContract.FAKE_HOST_SUFFIX), Arrays.randomNext(SocksRpcContract.FAKE_PORT_OBFS));

        Cache<BigInteger, Boolean> cache = Cache.getInstance();
        if (!cache.containsKey(hash)) {
            try {
                Tasks.runAsync(() -> {
                    facade.fakeEndpoint(hash, dstEpStr);
                    return true;
                }).whenCompleteAsync((r, e) -> {
                    if (BooleanUtils.isTrue(r)) {
                        cache.put(hash, r, CachePolicy.absolute(SocksRpcContract.FAKE_EXPIRE_SECONDS));
                    }
                }).get(SocksRpcContract.ASYNC_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.error("do fake", e);
            }
        }
        return destination;
    }

    public void initTransport(Channel channel) {
        Sockets.addTcpClientHandler(channel, config, next.getEndpoint().getEndpoint());
    }

    public void initProxyHandler(Channel channel) {
        AuthenticEndpoint svrEp = next.getEndpoint();
        Socks5ClientHandler proxyHandler = new Socks5ClientHandler(svrEp.getEndpoint(), svrEp.getUsername(), svrEp.getPassword());
        proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
        channel.pipeline().addLast(proxyHandler);
    }
}
