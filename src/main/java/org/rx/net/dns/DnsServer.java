package org.rx.net.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.net.Sockets;
import org.rx.net.support.SocksSupport;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DnsServer extends Disposable {
    final ServerBootstrap serverBootstrap;
    @Getter
    final Map<String, byte[]> customHosts = new ConcurrentHashMap<>(0);
    @Setter
    SocksSupport support;

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
}
