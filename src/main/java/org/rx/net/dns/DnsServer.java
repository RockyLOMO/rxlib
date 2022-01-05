package org.rx.net.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.TcpDnsQueryDecoder;
import io.netty.handler.codec.dns.TcpDnsResponseEncoder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.RandomList;
import org.rx.core.Arrays;
import org.rx.core.Disposable;
import org.rx.core.NQuery;
import org.rx.io.Files;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.support.UpstreamSupport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DnsServer extends Disposable {
    final ServerBootstrap serverBootstrap;
    @Setter
    int ttl = 1800;
    @Setter
    int hostsTtl = 180;
    @Getter
    final Map<String, RandomList<InetAddress>> hosts = new ConcurrentHashMap<>();
    @Setter
    RandomList<UpstreamSupport> shadowServers;

    public DnsServer() {
        this(53);
    }

    public DnsServer(int port, InetSocketAddress... nameServerList) {
        List<InetSocketAddress> addresses = Arrays.toList(nameServerList);

        serverBootstrap = Sockets.serverBootstrap(channel -> {
            channel.pipeline().addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder(),
                    new DnsHandler(DnsServer.this, true, addresses));
        });
        serverBootstrap.bind(port).addListener(Sockets.logBind(port));

        Bootstrap bootstrap = Sockets.udpBootstrap(MemoryMode.MEDIUM, channel -> {
            channel.pipeline().addLast(new DatagramDnsQueryDecoder(), new DatagramDnsResponseEncoder(),
                    new DnsHandler(DnsServer.this, false, addresses));
        });
        bootstrap.bind(port).addListener(Sockets.logBind(port));
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
        //根据权重取2个
        return NQuery.of(ips.next(), ips.next()).distinct().toList();
    }

    public List<InetAddress> getAllHosts(String host) {
        return new ArrayList<>(hosts.get(host));
    }

    @SneakyThrows
    public DnsServer addHosts(String host, @NonNull String... ips) {
        return addHosts(host, RandomList.DEFAULT_WEIGHT, NQuery.of(ips).select(InetAddress::getByName).toList());
    }

    public DnsServer addHosts(@NonNull String host, int weight, @NonNull Collection<InetAddress> ips) {
        RandomList<InetAddress> list = hosts.computeIfAbsent(host, k -> new RandomList<>());
        for (InetAddress ip : ips) {
            list.add(ip, weight);
        }
        return this;
    }

    public DnsServer removeHosts(@NonNull String host, Collection<InetAddress> ips) {
        hosts.computeIfAbsent(host, k -> new RandomList<>()).removeAll(ips);
        return this;
    }

    public DnsServer addHostsFile(String filePath) {
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
        return this;
    }
}
