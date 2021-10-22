package org.rx.net.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class DnsServer extends Disposable {
    final ServerBootstrap serverBootstrap;
    @Setter
    int ttl = 1800;
    @Setter
    int hostsTtl = 180;
    @Getter
    final Map<String, List<InetAddress>> hosts = new ConcurrentHashMap<>();
    @Setter
    RandomList<UpstreamSupport> shadowServers;

    public DnsServer() {
        this(53);
    }

    public DnsServer(int port, InetSocketAddress... nameServerList) {
        serverBootstrap = Sockets.serverBootstrap(channel -> {
            channel.pipeline().addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder(),
                    new DnsHandler(DnsServer.this, true, DnsServer.this.serverBootstrap.config().childGroup(), nameServerList));
        });
        serverBootstrap.bind(port).addListener(Sockets.logBind(port));

        Bootstrap bootstrap = Sockets.udpBootstrap(MemoryMode.MEDIUM, channel -> {
            channel.pipeline().addLast(new DatagramDnsQueryDecoder(), new DatagramDnsResponseEncoder(),
                    new DnsHandler(DnsServer.this, false, serverBootstrap.config().childGroup(), nameServerList));
        });
        bootstrap.bind(port).addListener(Sockets.logBind(port));
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(serverBootstrap);
    }

    @SneakyThrows
    public DnsServer addHosts(String host, @NonNull String... ips) {
        if (ips.length == 0) {
            return this;
        }

        return addHosts(host, NQuery.of(ips).select(InetAddress::getByName).toArray());
    }

    public DnsServer addHosts(@NonNull String host, @NonNull InetAddress... ips) {
        hosts.computeIfAbsent(host, k -> new CopyOnWriteArrayList<>()).addAll(Arrays.toList(ips));
        return this;
    }

    public DnsServer removeHosts(@NonNull String host, @NonNull InetAddress... ips) {
        hosts.computeIfAbsent(host, k -> new CopyOnWriteArrayList<>()).removeAll(Arrays.toList(ips));
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
