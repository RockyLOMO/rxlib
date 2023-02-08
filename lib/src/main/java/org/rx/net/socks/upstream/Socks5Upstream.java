package org.rx.net.socks.upstream;

import io.netty.channel.Channel;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.BooleanUtils;
import org.rx.codec.CodecUtil;
import org.rx.core.Arrays;
import org.rx.core.Cache;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.exception.InvalidException;
import org.rx.exception.TraceHandler;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.Sockets;
import org.rx.net.socks.SocksConfig;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
import org.rx.util.function.Func;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

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

        Sockets.addBackendHandler(channel, config, svrEp.getEndpoint());

        if (support != null
                && (SocksSupport.FAKE_IPS.contains(destination.getHost()) || SocksSupport.FAKE_PORTS.contains(destination.getPort())
                || !Sockets.isValidIp(destination.getHost()))) {
            String dstEpStr = destination.toString();
            BigInteger hash = CodecUtil.hashUnsigned64(dstEpStr.getBytes(StandardCharsets.UTF_8));
            //change dest first
            destination = new UnresolvedEndpoint(String.format("%s%s", hash, SocksSupport.FAKE_HOST_SUFFIX), Arrays.randomNext(SocksSupport.FAKE_PORT_OBFS));

            Cache<BigInteger, Boolean> cache = Cache.getInstance();
            if (!cache.containsKey(hash)) {
                try {
                    //Write the value after the current thread has timed out and the asynchronous thread is still executing successfully
                    Tasks.runAsync(() -> {
                        Sys.logCtx(String.format("socks5[%s]", config.getListenPort()), dstEpStr);
                        support.fakeEndpoint(hash, dstEpStr);
                        return true;
                    }).whenCompleteAsync((r, e) -> {
                        if (BooleanUtils.isTrue(r)) {
                            cache.put(hash, r);
                        }
                    }).get(SocksSupport.ASYNC_TIMEOUT, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    TraceHandler.INSTANCE.log(e);
                }
            }
//            Cache.getOrSet(hash, k -> awaitQuietly(() -> {
//                try {
//                    Sys.logCtx(String.format("socks5[%s]", config.getListenPort()), dstEpStr);
//                    support.fakeEndpoint(hash, dstEpStr);
//                    return true;
//                } catch (Throwable e) {
//                    TraceHandler.INSTANCE.log(e);
//                    return null;
//                }
//            }, SocksSupport.ASYNC_TIMEOUT));
        }

        Socks5ProxyHandler proxyHandler = new Socks5ProxyHandler(svrEp.getEndpoint(), svrEp.getUsername(), svrEp.getPassword());
        proxyHandler.setConnectTimeoutMillis(config.getConnectTimeoutMillis());
        channel.pipeline().addLast(proxyHandler);
    }
}
