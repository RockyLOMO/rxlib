package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.codec.CodecUtil;
import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.core.Sys;
import org.rx.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.TransportUtil;
import org.rx.net.socks.SocksConfig;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
import org.rx.util.function.Func;

import static org.rx.core.Tasks.awaitQuietly;

public class Socks5Upstream extends Upstream {
    final SocksConfig config; //Maybe frontend have a different configuration from backend
    final Func<UpstreamSupport> router;

    public Socks5Upstream(@NonNull UnresolvedEndpoint dstEp, @NonNull SocksConfig config, @NonNull Func<UpstreamSupport> router) {
        super(dstEp);
        this.config = config;
        this.router = router;
    }

    @SneakyThrows
    @Override
    public void initChannel(Channel channel) {
        UpstreamSupport next = router.invoke();
        if (next == null) {
            throw new InvalidException("ProxyHandlers is empty");
        }

        AuthenticEndpoint svrEp = next.getEndpoint();
        SocksSupport support = next.getSupport();

        TransportUtil.addBackendHandler(channel, config, svrEp.getEndpoint());

        if (support != null
                && (SocksSupport.FAKE_IPS.contains(destination.getHost()) || SocksSupport.FAKE_PORTS.contains(destination.getPort())
                || !Sockets.isValidIp(destination.getHost()))) {
            String dstEpStr = destination.toString();
            long hash = CodecUtil.hash64(dstEpStr);
            //change dest first
            destination = new UnresolvedEndpoint(String.format("%s%s", hash, SocksSupport.FAKE_HOST_SUFFIX), Arrays.randomNext(SocksSupport.FAKE_PORT_OBFS));
            Cache.getOrSet(hash, k -> awaitQuietly(() -> {
                Sys.logCtx(String.format("socks5[%s]", config.getListenPort()), dstEpStr);
                support.fakeEndpoint(hash, dstEpStr);
                return true;
            }, SocksSupport.ASYNC_TIMEOUT));
        }

        Socks5ProxyHandler proxyHandler = new Socks5ProxyHandler(svrEp.getEndpoint(), svrEp.getUsername(), svrEp.getPassword());
        proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
        channel.pipeline().addLast(proxyHandler);
    }
}
