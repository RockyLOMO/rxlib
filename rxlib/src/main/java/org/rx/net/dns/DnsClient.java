package org.rx.net.dns;

import io.netty.channel.AddressedEnvelope;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.*;
import io.netty.util.concurrent.Future;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.RxConfig;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    static volatile DnsClient inlandClient, outlandClient;

    public static DnsClient inlandClient() {
        if (inlandClient == null) {
            synchronized (DnsClient.class) {
                if (inlandClient == null) {
                    inlandClient = new DnsClient(inlandNameServers());
                }
            }
        }
        return inlandClient;
    }

    public static DnsClient outlandClient() {
        if (outlandClient == null) {
            synchronized (DnsClient.class) {
                if (outlandClient == null) {
                    outlandClient = new DnsClient(outlandNameServers());
                }
            }
        }
        return outlandClient;
    }

    public static List<InetSocketAddress> inlandNameServers() {
        return parseNameServers(RxConfig.INSTANCE.getNet().getDns().getInlandServers());
    }

    public static List<InetSocketAddress> outlandNameServers() {
        return parseNameServers(RxConfig.INSTANCE.getNet().getDns().getOutlandServers());
    }

    public static DnsServerAddressStreamProvider nameServerProvider(Collection<InetSocketAddress> nameServerList) {
        if (nameServerList == null || nameServerList.isEmpty()) {
            return DnsServerAddressStreamProviders.platformDefault();
        }
        return new DnsServerAddressStreamProviderImpl(new LinkedHashSet<>(nameServerList));
    }

    static List<InetSocketAddress> parseNameServers(Collection<String> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<InetSocketAddress> result = new LinkedHashSet<>(endpoints.size());
        for (String endpoint : endpoints) {
            if (endpoint == null || endpoint.trim().isEmpty()) {
                continue;
            }
            try {
                result.add(Sockets.parseEndpoint(endpoint.trim()));
            } catch (Exception e) {
                log.warn("Ignore invalid dns server {}", endpoint, e);
            }
        }
        if (result.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(result);
    }

    @Getter
    final DnsServerAddressStreamProvider nameServerProvider;
    final DnsNameResolver nameResolver;

    public DnsClient(@NonNull Collection<InetSocketAddress> nameServerList) {
        nameServerProvider = nameServerProvider(nameServerList);
        nameResolver = new DnsNameResolverBuilder(Sockets.reactor(Sockets.ReactorNames.SHARED_UDP, false).next())
                .nameServerProvider(nameServerProvider)
                .channelType(Sockets.udpChannelClass())
                .socketChannelType(Sockets.tcpChannelClass())
                .ttl(5, 300)
                .negativeTtl(5)
                .queryTimeoutMillis(TimeUnit.SECONDS.toMillis(5))
                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                .recursionDesired(true)
                .maxQueriesPerResolve(8)
                .ndots(1)
                .build();
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

    public Future<InetAddress> resolveAsync(String inetHost) {
        return nameResolver.resolve(inetHost);
    }

    public Future<List<InetAddress>> resolveAllAsync(String inetHost) {
        return nameResolver.resolveAll(inetHost);
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
