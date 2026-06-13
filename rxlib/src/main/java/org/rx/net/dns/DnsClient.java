package org.rx.net.dns;

import io.netty.channel.AddressedEnvelope;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.*;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.RxConfig;
import org.rx.exception.InvalidException;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.rx.core.Tasks.await;
import static org.rx.core.Extends.quietly;

@Slf4j
public class DnsClient extends DnsResolverSupport {
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

    static volatile DnsClient directClient, remoteClient;

    public static DnsClient directClient() {
        if (directClient == null) {
            synchronized (DnsClient.class) {
                if (directClient == null) {
                    List<InetSocketAddress> nameServers = directNameServers();
                    directClient = new DnsClient(nameServers, nameServers.isEmpty() || localSystemFallback());
                }
            }
        }
        return directClient;
    }

    public static DnsClient remoteClient() {
        if (remoteClient == null) {
            synchronized (DnsClient.class) {
                if (remoteClient == null) {
                    remoteClient = new DnsClient(remoteNameServers(), localSystemFallback());
                }
            }
        }
        return remoteClient;
    }

    public static List<InetSocketAddress> directNameServers() {
        List<InetSocketAddress> configured = parseNameServers(RxConfig.INSTANCE.getNet().getDns().getDirectServers());
        List<InetSocketAddress> injected = Sockets.injectedNameServers();
        if (injected.isEmpty()) {
            return configured;
        }

        LinkedHashSet<InetSocketAddress> result = new LinkedHashSet<InetSocketAddress>(injected.size() + configured.size());
        result.addAll(injected);
        result.addAll(configured);
        return new ArrayList<InetSocketAddress>(result);
    }

    public static List<InetSocketAddress> remoteNameServers() {
        return parseNameServers(RxConfig.INSTANCE.getNet().getDns().getRemoteServers());
    }

    public static DnsServerAddressStreamProvider nameServerProvider(Collection<InetSocketAddress> nameServerList) {
        return nameServerProvider(nameServerList, localSystemFallback());
    }

    public static DnsServerAddressStreamProvider nameServerProvider(Collection<InetSocketAddress> nameServerList,
                                                                    boolean localSystemFallback) {
        if (nameServerList == null || nameServerList.isEmpty()) {
            if (localSystemFallback) {
                return DnsServerAddressStreamProviders.platformDefault();
            }
            throw new InvalidException("Empty dns server list and local system fallback disabled");
        }
        List<InetSocketAddress> servers = sanitizeNameServers(nameServerList);
        if (servers.isEmpty()) {
            if (localSystemFallback) {
                log.warn("No resolved dns server available, fallback to platform default");
                return DnsServerAddressStreamProviders.platformDefault();
            }
            throw new InvalidException("No resolved dns server available and local system fallback disabled");
        }
        return new DnsServerAddressStreamProviderImpl(servers);
    }

    public static boolean localSystemFallback() {
        return RxConfig.INSTANCE.getNet().getDns().isLocalSystemFallback();
    }

    public static void resetDirectClient() {
        DnsClient old;
        synchronized (DnsClient.class) {
            old = directClient;
            directClient = null;
        }
        if (old != null) {
            quietly(old::close);
        }
    }

    static List<InetSocketAddress> sanitizeNameServers(Collection<InetSocketAddress> nameServerList) {
        LinkedHashSet<InetSocketAddress> servers = new LinkedHashSet<>(nameServerList.size());
        for (InetSocketAddress endpoint : nameServerList) {
            if (endpoint == null) {
                continue;
            }

            InetAddress address = endpoint.getAddress();
            if (address != null) {
                servers.add(new InetSocketAddress(address, endpoint.getPort()));
                continue;
            }

            // Netty DNS server stream rejects unresolved server endpoints; resolve once during client init.
            String host = endpoint.getHostString();
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress item : addresses) {
                    servers.add(new InetSocketAddress(item, endpoint.getPort()));
                }
            } catch (UnknownHostException e) {
                log.warn("Ignore unresolved dns server {}", endpoint, e);
            }
        }
        return new ArrayList<>(servers);
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
    final EventLoop executor;
    final DnsNameResolver nameResolver;

    public DnsClient(@NonNull Collection<InetSocketAddress> nameServerList) {
        this(nameServerList, localSystemFallback());
    }

    public DnsClient(@NonNull Collection<InetSocketAddress> nameServerList, boolean localSystemFallback) {
        nameServerProvider = nameServerProvider(nameServerList, localSystemFallback);
        executor = Sockets.reactor(Sockets.ReactorNames.SHARED_UDP, false).next();
        nameResolver = new DnsNameResolverBuilder(executor)
                .nameServerProvider(nameServerProvider)
                .channelType(Sockets.udpChannelClass())
                .socketChannelType(Sockets.tcpChannelClass())
                .ttl(5, 300)
                .negativeTtl(DnsServer.DEFAULT_NEGATIVE_TTL)
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
        Future<List<InetAddress>> local = resolveLocalAllAsync(null, inetHost, null, executor);
        if (local == null) {
            return nameResolver.resolve(inetHost);
        }

        Promise<InetAddress> promise = executor.newPromise();
        local.addListener(f -> {
            if (!f.isSuccess()) {
                promise.tryFailure(f.cause());
                return;
            }

            List<InetAddress> ips = ((Future<List<InetAddress>>) f).getNow();
            if (ips == null) {
                nameResolver.resolve(inetHost).addListener(upstream -> {
                    if (upstream.isSuccess()) {
                        promise.trySuccess(((Future<InetAddress>) upstream).getNow());
                    } else {
                        promise.tryFailure(upstream.cause());
                    }
                });
                return;
            }
            if (ips.isEmpty()) {
                promise.tryFailure(new UnknownHostException(inetHost));
                return;
            }
            promise.trySuccess(ips.get(0));
        });
        return promise;
    }

    public Future<List<InetAddress>> resolveAllAsync(String inetHost) {
        Future<List<InetAddress>> local = resolveLocalAllAsync(null, inetHost, null, executor);
        if (local == null) {
            return nameResolver.resolveAll(inetHost);
        }

        Promise<List<InetAddress>> promise = executor.newPromise();
        local.addListener(f -> {
            if (!f.isSuccess()) {
                promise.tryFailure(f.cause());
                return;
            }

            List<InetAddress> ips = ((Future<List<InetAddress>>) f).getNow();
            if (ips != null) {
                promise.trySuccess(ips);
                return;
            }
            nameResolver.resolveAll(inetHost).addListener(upstream -> {
                if (upstream.isSuccess()) {
                    promise.trySuccess(((Future<List<InetAddress>>) upstream).getNow());
                } else {
                    promise.tryFailure(upstream.cause());
                }
            });
        });
        return promise;
    }

    public InetAddress resolve(String inetHost) {
        return await(resolveAsync(inetHost));
    }

    public List<InetAddress> resolveAll(String inetHost) {
        return await(resolveAllAsync(inetHost));
    }

    public void clearCache() {
        nameResolver.resolveCache().clear();
        if (interceptorCache != null) {
            interceptorCache.clear();
        }
        resolvingPromises.clear();
        domainKeyCache.clear();
    }
}
