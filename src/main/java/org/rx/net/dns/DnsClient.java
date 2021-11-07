package org.rx.net.dns;

import io.netty.channel.AddressedEnvelope;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.resolver.dns.*;
import io.netty.util.concurrent.Future;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.App.tryClose;
import static org.rx.core.Tasks.await;

@Slf4j
public class DnsClient extends Disposable {
    static class DnsServerAddressStreamProviderImpl implements DnsServerAddressStreamProvider {
        final DnsServerAddresses nameServer;

        public DnsServerAddressStreamProviderImpl(Iterable<InetSocketAddress> nameServerList) {
            nameServer = DnsServerAddresses.sequential(nameServerList);
        }

        @Override
        public DnsServerAddressStream nameServerAddressStream(String hostname) {
            return nameServer.stream();
        }
    }

    static final int MAX_FAIL_COUNT = 4;

    public static DnsClient inlandClient() {
        return new DnsClient(Arrays.toList(Sockets.parseEndpoint("114.114.114.114:53")));
    }

    public static DnsClient outlandClient() {
        return new DnsClient(Arrays.toList(Sockets.parseEndpoint("8.8.8.8:53"), Sockets.parseEndpoint("1.1.1.1:53")));
    }

    @Getter
    final Set<InetSocketAddress> serverAddresses;
    private volatile DnsNameResolver nameResolver;
    final AtomicInteger failCount = new AtomicInteger();

    public DnsClient(@NonNull List<InetSocketAddress> nameServerList) {
        serverAddresses = new LinkedHashSet<>(nameServerList);
        renewResolver();
    }

    @Override
    protected void freeObjects() {
        nameResolver.close();
    }

    public Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> query(DnsQuestion question) {
        return nameResolver.query(question).addListener(f -> {
            if (f.isSuccess()
                    || !(f.cause() instanceof DnsNameResolverException)
                    || !Strings.contains(f.cause().getMessage(), "failed to send a query via UDP")) {
                return;
            }

            if (failCount.incrementAndGet() > MAX_FAIL_COUNT) {
//                Tasks.setTimeout(this::renewResolver, 500, this, TimeoutFlag.SINGLE);
                log.warn("renewResolver 4 {}", question);
            }
        });
    }

    void renewResolver() {
        tryClose(nameResolver);
        nameResolver = new DnsNameResolverBuilder(Sockets.getUdpEventLoop().next())
                .nameServerProvider(!serverAddresses.isEmpty() ? new DnsServerAddressStreamProviderImpl(serverAddresses)
                        : DnsServerAddressStreamProviders.platformDefault())
                .channelType(NioDatagramChannel.class)
                .socketChannelType(Sockets.channelClass()).build();
        failCount.set(0);
    }

    public InetAddress resolve(String inetHost) {
        return await(nameResolver.resolve(inetHost));
    }

    public List<InetAddress> resolveAll(String inetHost) {
        return await(nameResolver.resolveAll(inetHost));
    }

    public void clearCache() {
        nameResolver.resolveCache().clear();
    }
}
