package org.rx.net.dns;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Promise;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.Cache;
import org.rx.core.Disposable;
import org.rx.core.Linq;
import org.rx.core.cache.H2StoreCache;
import org.rx.core.cache.MemoryCache;
import org.rx.io.Files;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DnsServer extends Disposable {
    public static final int DEFAULT_NEGATIVE_TTL = 5;

    public interface ResolveInterceptor {
        List<InetAddress> resolveHost(InetAddress srcIp, String host);
    }

    static final String DOMAIN_PREFIX = "_dns:";
    static final AttributeKey<DnsServer> ATTR_SVR = AttributeKey.valueOf("svr");
    static final AttributeKey<DnsClient> ATTR_UPSTREAM = AttributeKey.valueOf("upstream");
    static final AttributeKey<InetSocketAddress> ATTR_UDP_SENDER = AttributeKey.valueOf("dnsUdpSender");
    static final long DEFAULT_INTERCEPTOR_BREAKER_MILLIS = 30_000L;
    final ServerBootstrap serverBootstrap;
    final DnsClient upstreamClient;
    final List<Channel> tcpChannels;
    final List<Channel> udpChannels;
    @Setter
    int ttl = 1800;
    @Setter
    int hostsTtl = 180;
    @Setter
    boolean enableHostsWeight;
    @Getter
    final Map<String, RandomList<InetAddress>> hosts = new ConcurrentHashMap<>();
    @Getter
    @Setter
    int negativeTtl = DEFAULT_NEGATIVE_TTL;
    RandomList<ResolveInterceptor> interceptors;
    Cache<String, List<InetAddress>> interceptorCache;
    final Cache<String, String> domainKeyCache = new MemoryCache<>(b -> b.maximumSize(4096));
    // Tracks keys currently being resolved to prevent thundering-herd cache stampede
    final Set<String> resolvingKeys = ConcurrentHashMap.newKeySet();
    final Map<String, Promise<List<InetAddress>>> resolvingPromises = new ConcurrentHashMap<>();
    final Map<ResolveInterceptor, Long> interceptorBreakerUntil = new ConcurrentHashMap<>();
    @Setter
    long interceptorBreakerOpenMillis = DEFAULT_INTERCEPTOR_BREAKER_MILLIS;
    @Getter
    volatile DnsDoHConfig dohConfig = new DnsDoHConfig();

    public void setInterceptors(RandomList<ResolveInterceptor> interceptors) {
        if (CollectionUtils.isEmpty(interceptors)) {
            this.interceptors = null;
            interceptorCache = null;
            interceptorBreakerUntil.clear();
            return;
        }

        H2StoreCache<Object, Object> cache = (H2StoreCache<Object, Object>) H2StoreCache.DEFAULT;
        //todo srcIp
//        cache.onExpired.add((s, entry) -> {
//            String key;
//            if ((key = as(entry.getKey(), String.class)) == null || !key.startsWith(DOMAIN_PREFIX)) {
//                return;
//            }
//
//            String domain = key.substring(DOMAIN_PREFIX.length());
//            List<InetAddress> lastIps = (List<InetAddress>) entry.getValue();
//            Tasks.run(() -> cache.get(key, k -> {
//                List<InetAddress> sIps = interceptors.next().resolveHost(null, domain);
//                if (CollectionUtils.isEmpty(sIps)) {
//                    return null;
//                }
//                log.info("dns renew {} -> {} <- last={}", domain, sIps, lastIps);
//                return sIps;
//            }, CachePolicy.absolute(ttl)));
//        });
        interceptorCache = (Cache) cache;
        this.interceptors = interceptors;
        retainInterceptorBreakerKeys(interceptors.aliveList());
    }

    boolean isInterceptorAvailable(ResolveInterceptor interceptor, long nowMillis) {
        Long until = interceptorBreakerUntil.get(interceptor);
        if (until == null) {
            return true;
        }
        if (until <= nowMillis) {
            interceptorBreakerUntil.remove(interceptor, until);
            return true;
        }
        return false;
    }

    void markInterceptorSuccess(ResolveInterceptor interceptor) {
        interceptorBreakerUntil.remove(interceptor);
    }

    void markInterceptorFailure(ResolveInterceptor interceptor) {
        long openMillis = interceptorBreakerOpenMillis;
        if (openMillis <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + openMillis;
        interceptorBreakerUntil.put(interceptor, until < 0 ? Long.MAX_VALUE : until);
    }

    void retainInterceptorBreakerKeys(Collection<ResolveInterceptor> aliveInterceptors) {
        if (!interceptorBreakerUntil.isEmpty()) {
            interceptorBreakerUntil.keySet().retainAll(aliveInterceptors);
        }
    }

    public DnsServer(int port) {
        this(port, null);
    }

    //AES or TLS mainly for TCP
    public DnsServer(int port, Collection<InetSocketAddress> nameServerList) {
        if (nameServerList == null) {
            nameServerList = Collections.emptyList();
        }

        upstreamClient = new DnsClient(nameServerList);
        serverBootstrap = Sockets.serverBootstrap(channel -> channel.pipeline().addLast(new DnsTcpPortMuxHandler(this)))
                .attr(ATTR_SVR, this).attr(ATTR_UPSTREAM, upstreamClient);
        InetSocketAddress bindAddress = Sockets.newAnyEndpoint(port);
        tcpChannels = Sockets.bindChannels(serverBootstrap, bindAddress, null);

        io.netty.bootstrap.Bootstrap udpBootstrap = Sockets.udpBootstrap(null, channel -> channel.pipeline().addLast(
                        DnsDatagramSourceHandler.DEFAULT, new DatagramDnsQueryDecoder(), new DatagramDnsResponseEncoder(), DnsHandler.DEFAULT))
                .attr(ATTR_SVR, this).attr(ATTR_UPSTREAM, upstreamClient);
        udpChannels = Sockets.bindChannels(udpBootstrap, bindAddress, null);
    }

    @Override
    protected void dispose() {
        for (Channel channel : tcpChannels) {
            closeChannel(channel);
        }
        for (Channel channel : udpChannels) {
            closeChannel(channel);
        }
        upstreamClient.close();
        Sockets.closeBootstrap(serverBootstrap);
    }

    private void closeChannel(Channel channel) {
        if (channel != null) {
            if (channel.eventLoop().inEventLoop()) {
                channel.close();
            } else {
                channel.close().syncUninterruptibly();
            }
        }
    }

    public List<InetAddress> getHosts(String host) {
        RandomList<InetAddress> ips = hosts.get(host);
        if (CollectionUtils.isEmpty(ips)) {
            return Collections.emptyList();
        }
        if (ips.size() == 1) {
            return Collections.singletonList(ips.get(0));
        }
        return enableHostsWeight ? weightedHosts(ips) : ips.readOnlySnapshot();
    }

    public List<InetAddress> getAllHosts(String host) {
        RandomList<InetAddress> ips = hosts.get(host);
        if (CollectionUtils.isEmpty(ips)) {
            return Collections.emptyList();
        }
        return new ArrayList<>(ips);
    }

    String cacheKey(String domain) {
        return domainKeyCache.get(domain, k -> DOMAIN_PREFIX.concat(k));
    }

    String cacheKey(String domain, DnsRecordType queryType) {
        return domainKeyCache.get(queryType.name() + ":" + domain,
                k -> DOMAIN_PREFIX.concat("int:").concat(queryType.name()).concat(":").concat(domain));
    }

    String resolveKey(String domain) {
        return domainKeyCache.get("*:" + domain, k -> DOMAIN_PREFIX.concat("int:*:").concat(domain));
    }

    public DnsServer enableDoH(DnsDoHConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config");
        }
        config.setEnabled(true);
        dohConfig = config;
        return this;
    }

    private List<InetAddress> weightedHosts(RandomList<InetAddress> ips) {
        InetAddress first = ips.next();
        InetAddress second = ips.next();
        if (Objects.equals(first, second)) {
            return Collections.singletonList(first);
        }
        return Arrays.asList(first, second);
    }

    public boolean addHosts(String host, @NonNull String... ips) {
        return addHosts(host, RandomList.DEFAULT_WEIGHT, Linq.from(ips).select(InetAddress::getByName).toList());
    }

    public boolean addHosts(@NonNull String host, int weight, @NonNull Collection<InetAddress> ips) {
        boolean changed = false;
        RandomList<InetAddress> list = hosts.computeIfAbsent(host, k -> new RandomList<>());
        for (InetAddress ip : Linq.from(ips).distinct()) {
            // addOrUpdate holds RandomList's own write lock — no external synchronized needed
            if (list.addOrUpdate(ip, weight)) {
                changed = true;
            }
        }
        return changed;
    }

    public boolean removeHosts(@NonNull String host, Collection<InetAddress> ips) {
        RandomList<InetAddress> list = hosts.get(host);
        if (list == null) {
            return false;
        }
        return list.removeAll(ips);
    }

    public void addHostsFile(String filePath) {
        Path path = Paths.get(filePath);
        if (!java.nio.file.Files.isRegularFile(path)) {
            log.warn("Hosts file not found, skip {}", filePath);
            return;
        }
        Files.readLines(filePath, line -> {
            if (line.startsWith("#") || line.trim().isEmpty()) {
                return;
            }

            String trimmed = line.trim();
            int spaceIdx = trimmed.indexOf(' ');
            int tabIdx = trimmed.indexOf('\t');

            int splitIdx;
            if (spaceIdx == -1 && tabIdx == -1) {
                log.warn("Invalid line (no delimiter): {}", line);
                return;
            } else if (spaceIdx == -1) {
                splitIdx = tabIdx;
            } else if (tabIdx == -1) {
                splitIdx = spaceIdx;
            } else {
                splitIdx = Math.min(spaceIdx, tabIdx);
            }

            String ip = trimmed.substring(0, splitIdx);
            String host = trimmed.substring(splitIdx + 1).trim();
            addHosts(host, ip);
        });
    }
}
