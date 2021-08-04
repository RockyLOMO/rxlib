package org.rx.net.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RandomList;
import org.rx.core.Disposable;
import org.rx.io.Files;
import org.rx.net.Sockets;
import org.rx.net.support.UpstreamSupport;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DnsServer extends Disposable {
    final ServerBootstrap serverBootstrap;
    @Getter
    final Map<String, byte[]> customHosts = new ConcurrentHashMap<>();
    @Setter
    int ttl = 1800;
    @Setter
    RandomList<UpstreamSupport> support;

    public DnsServer() {
        this(53);
    }

    public DnsServer(int port, InetSocketAddress... nameServerList) {
        serverBootstrap = Sockets.serverBootstrap(channel -> {
            channel.pipeline().addLast(new TcpDnsQueryDecoder(), new TcpDnsResponseEncoder(),
                    new DnsHandler(DnsServer.this, true, DnsServer.this.serverBootstrap.config().childGroup(), nameServerList));
        });
        serverBootstrap.bind(port).addListener(Sockets.logBind(port));

        Bootstrap bootstrap = Sockets.udpBootstrap(true).handler(new ChannelInitializer<NioDatagramChannel>() {
            @Override
            protected void initChannel(NioDatagramChannel channel) {
                channel.pipeline().addLast(new DatagramDnsQueryDecoder(), new DatagramDnsResponseEncoder(),
                        new DnsHandler(DnsServer.this, false, serverBootstrap.config().childGroup(), nameServerList));
            }
        });
        bootstrap.bind(port).addListener(Sockets.logBind(port));
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(serverBootstrap);
    }

    @SneakyThrows
    public DnsServer addHosts(String host, String ip) {
        customHosts.put(host, InetAddress.getByName(ip).getAddress());
        return this;
    }

    public DnsServer addHostsFile(String filePath) {
        Files.readLines(filePath).forEach(line -> {
            String t = "\t";
            int s = line.indexOf(t), e = line.lastIndexOf(t);
            if (s == -1 || e == -1) {
                log.warn("Invalid line {}", line);
                return;
            }
            addHosts(line.substring(0, s), line.substring(e + t.length()));
        });
        return this;
    }
}
