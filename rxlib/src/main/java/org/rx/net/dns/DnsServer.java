package org.rx.net.dns;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.TcpDnsQueryDecoder;
import io.netty.handler.codec.dns.TcpDnsResponseEncoder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.*;
import org.rx.core.cache.DiskCache;
import org.rx.io.Files;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.core.Extends.as;

@Slf4j
public class DnsServer extends Disposable {
    public interface ResolveInterceptor {
        List<InetAddress> resolveHost(String host);
    }

    //    static final boolean ENABLE_AUTO_RENEW = false;
    static final String DOMAIN_PREFIX = "resolveHost:";
    final ServerBootstrap serverBootstrap;
    @Setter
    int ttl = 1800;
    @Setter
    int hostsTtl = 180;
    @Setter
    boolean enableHostsWeight;
    @Getter
    final Map<String, RandomList<InetAddress>> hosts = new ConcurrentHashMap<>();
    RandomList<ResolveInterceptor> interceptors;
    Cache<String, List<InetAddress>> interceptorCache;

    public void setInterceptors(RandomList<ResolveInterceptor> interceptors) {
        if (CollectionUtils.isEmpty(this.interceptors = interceptors)) {
            return;
        }

        DiskCache<Object, Object> cache = (DiskCache<Object, Object>) DiskCache.DEFAULT;
//        if (ENABLE_AUTO_RENEW) {
        cache.onExpired.combine((s, entry) -> {
            String key;
            if ((key = as(entry.getKey(), String.class)) == null || !key.startsWith(DOMAIN_PREFIX)) {
                return;
            }

            String domain = key.substring(DOMAIN_PREFIX.length());
            List<InetAddress> lastIps = (List<InetAddress>) entry.getValue();
            Tasks.run(() -> cache.get(key, k -> {
                List<InetAddress> sIps = interceptors.next().resolveHost(domain);
                if (CollectionUtils.isEmpty(sIps)) {
                    return null;
                }
                log.info("renewAsync {} lastAddresses={} addresses={}", key, lastIps, sIps);
                return sIps;
            }, CachePolicy.absolute(ttl)));
        });
//        }
        interceptorCache = (Cache) cache;
    }

    public DnsServer(int port) {
        this(port, null);
    }

    //AES or TLS mainly for TCP
    public DnsServer(int port, Collection<InetSocketAddress> nameServerList) {
        if (nameServerList == null) {
            nameServerList = Collections.emptyList();
        }

        DnsHandler tcpHandler = new DnsHandler(DnsServer.this, true, nameServerList);
        serverBootstrap = Sockets.serverBootstrap(channel -> channel.pipeline().addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder(), tcpHandler));
        serverBootstrap.bind(port).addListener(Sockets.logBind(port));

        DnsHandler udpHandler = new DnsHandler(DnsServer.this, false, nameServerList);
        Sockets.udpBootstrap(Sockets.ReactorNames.DNS, MemoryMode.MEDIUM, channel -> channel.pipeline().addLast(new DatagramDnsQueryDecoder(), new DatagramDnsResponseEncoder(), udpHandler))
                .bind(port).addListener(Sockets.logBind(port));
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(serverBootstrap);
    }

    public List<InetAddress> getHosts(String host) {
        RandomList<InetAddress> ips = hosts.get(host);
        if (CollectionUtils.isEmpty(ips)) {
            return Collections.emptyList();
        }
        return enableHostsWeight ? Linq.from(ips.next(), ips.next()).distinct().toList() : new ArrayList<>(ips);
    }

    public List<InetAddress> getAllHosts(String host) {
        RandomList<InetAddress> ips = hosts.get(host);
        if (ips == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(ips);
    }

    public boolean addHosts(String host, @NonNull String... ips) {
        return addHosts(host, RandomList.DEFAULT_WEIGHT, Linq.from(ips).select(InetAddress::getByName).toSet());
    }

    public boolean addHosts(@NonNull String host, int weight, @NonNull Collection<InetAddress> ips) {
        boolean changed = false;
        RandomList<InetAddress> list = hosts.computeIfAbsent(host, k -> new RandomList<>());
        for (InetAddress ip : ips) {
            synchronized (list) {
                if (list.contains(ip)) {
                    list.setWeight(ip, weight);
                    continue;
                }
                list.add(ip, weight);
                changed = true;
            }
        }
        return changed;
    }

    public boolean removeHosts(@NonNull String host, Collection<InetAddress> ips) {
        return hosts.computeIfAbsent(host, k -> new RandomList<>()).removeAll(ips);
    }

    public void addHostsFile(String filePath) {
        Files.readLines(filePath).forEach(line -> {
            if (line.startsWith("#")) {
                return;
            }

            String t = "\t";
            int s = line.indexOf(t), e = line.lastIndexOf(t);
            if (s == -1 || e == -1) {
                log.warn("Invalid line {}", line);
                return;
            }
            addHosts(line.substring(e + t.length()), line.substring(0, s));
        });
    }
}
