package org.rx.net.dns;

import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.*;
import lombok.Getter;
import lombok.NonNull;
import org.rx.core.Arrays;
import org.rx.core.Disposable;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
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

    final DnsNameResolver nameResolver;
    @Getter
    final Set<InetSocketAddress> serverAddresses;

    public DnsClient(@NonNull List<InetSocketAddress> nameServerList) {
        serverAddresses = new LinkedHashSet<>(nameServerList);
        nameResolver = new DnsNameResolverBuilder(Sockets.getUdpEventLoop().next())
                .nameServerProvider(!serverAddresses.isEmpty() ? new DnsServerAddressStreamProviderImpl(serverAddresses)
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

    public void clearCache() {
        nameResolver.resolveCache().clear();
    }
}
