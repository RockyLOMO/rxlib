package org.rx.net.dns;

import io.netty.channel.AddressedEnvelope;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.resolver.dns.*;
import io.netty.util.concurrent.Future;
import lombok.Getter;
import lombok.NonNull;
import org.rx.core.Arrays;
import org.rx.core.Disposable;
import org.rx.exception.TraceHandler;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.rx.core.Tasks.await;

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

    public static DnsClient inlandClient() {
        return new DnsClient(Arrays.toList(Sockets.parseEndpoint("114.114.114.114:53")));
    }

    public static DnsClient outlandClient() {
        return new DnsClient(Arrays.toList(Sockets.parseEndpoint("8.8.8.8:53"), Sockets.parseEndpoint("1.1.1.1:53")));
    }

    @Getter
    final Set<InetSocketAddress> serverEndpoints;
    final DnsNameResolver nameResolver;

    public DnsClient(@NonNull Collection<InetSocketAddress> nameServerList) {
        serverEndpoints = new LinkedHashSet<>(nameServerList);
        nameResolver = new DnsNameResolverBuilder(Sockets.udpReactor().next())
                .nameServerProvider(!serverEndpoints.isEmpty() ? new DnsServerAddressStreamProviderImpl(serverEndpoints)
                        : DnsServerAddressStreamProviders.platformDefault())
                .channelType(NioDatagramChannel.class)
                .socketChannelType(Sockets.channelClass()).build();
    }

    @Override
    protected void freeObjects() {
        nameResolver.close();
    }

    public Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> query(DnsQuestion question) {
        return nameResolver.query(question).addListener(f -> {
            if (f.isSuccess()) {
                return;
            }

            TraceHandler.INSTANCE.log("Dns query fail question={}", question, f.cause());
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
