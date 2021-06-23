package org.rx.net.dns;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.*;
import lombok.NonNull;
import org.rx.core.Arrays;
import org.rx.core.Disposable;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.rx.core.Tasks.await;

public class DnsClient extends Disposable {
    static class DnsServerAddressStreamProviderImpl implements DnsServerAddressStreamProvider {
        final DnsServerAddresses nameServer;

        public DnsServerAddressStreamProviderImpl(List<InetSocketAddress> nameServerList) {
            nameServer = DnsServerAddresses.sequential(nameServerList);
        }

        @Override
        public DnsServerAddressStream nameServerAddressStream(String hostname) {
            return nameServer.stream();
        }
    }

    public static DnsClient inlandServerList() {
        return new DnsClient(Sockets.parseEndpoint("114.114.114.114:53"));
    }

    public static DnsClient outlandClient() {
        return new DnsClient(Sockets.parseEndpoint("8.8.8.8:53"), Sockets.parseEndpoint("1.1.1.1:53"));
    }

    final DnsNameResolver nameResolver;

    public DnsClient(@NonNull InetSocketAddress... nameServerList) {
        this(Sockets.reactorEventLoop(DnsClient.class.getSimpleName()), nameServerList);
    }

    public DnsClient(@NonNull EventLoopGroup eventLoopGroup, @NonNull InetSocketAddress... nameServerList) {
        nameResolver = new DnsNameResolverBuilder(eventLoopGroup.next())
                .nameServerProvider(nameServerList.length > 0 ? new DnsServerAddressStreamProviderImpl(Arrays.toList(nameServerList))
                        : DnsServerAddressStreamProviders.platformDefault())
                .channelType(NioDatagramChannel.class)
                .socketChannelType(Sockets.channelClass()).build();
    }

    @Override
    protected void freeObjects() {
        nameResolver.close();
    }

    public InetAddress resolve(String inetHost) {
        return await(nameResolver.resolve(inetHost));
    }

    public List<InetAddress> resolveAll(String inetHost) {
        return await(nameResolver.resolveAll(inetHost));
    }
}
