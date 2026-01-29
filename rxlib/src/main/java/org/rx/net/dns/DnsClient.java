package org.rx.net.dns;

import io.netty.channel.AddressedEnvelope;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.resolver.dns.*;
import io.netty.util.concurrent.Future;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Linq;
import org.rx.core.RxConfig;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

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

    static DnsClient inlandClient, outlandClient;

    public static DnsClient inlandClient() {
        if (inlandClient == null) {
            inlandClient = new DnsClient(Linq.from(RxConfig.INSTANCE.getNet().getDns().getInlandServers()).select(Sockets::parseEndpoint).toList());
        }
        return inlandClient;
    }

    public static DnsClient outlandClient() {
        if (outlandClient == null) {
            outlandClient = new DnsClient(Linq.from(RxConfig.INSTANCE.getNet().getDns().getOutlandServers()).select(Sockets::parseEndpoint).toList());
        }
        return outlandClient;
    }

    final DnsNameResolver nameResolver;

    public DnsClient(@NonNull Collection<InetSocketAddress> nameServerList) {
        nameResolver = new DnsNameResolverBuilder(Sockets.reactor(Sockets.ReactorNames.SHARED_UDP, false).next())
                .nameServerProvider(!nameServerList.isEmpty() ? new DnsServerAddressStreamProviderImpl(new LinkedHashSet<>(nameServerList))
                        : DnsServerAddressStreamProviders.platformDefault())
                .channelType(Sockets.udpChannelClass())
                .socketChannelType(Sockets.tcpChannelClass()).build();
    }

    @Override
    protected void dispose() {
        nameResolver.close();
    }

    public Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> query(DnsQuestion question) {
        return nameResolver.query(question).addListener(f -> {
            if (f.isSuccess()) {
                return;
            }

            log.error("Dns query fail question={}", question, f.cause());
        });
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
