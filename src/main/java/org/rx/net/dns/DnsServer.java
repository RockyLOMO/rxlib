package org.rx.net.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
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
    final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();
    @Getter
    final Map<String, byte[]> customHosts = new ConcurrentHashMap<>(0);
    @Setter
    SocksSupport support;

    public DnsServer() {
        this(53);
    }

    public DnsServer(int port, InetSocketAddress... nameServerList) {
        Bootstrap bootstrap = Sockets.udpBootstrap(eventLoopGroup, true).handler(new ChannelInitializer<NioDatagramChannel>() {
            @Override
            protected void initChannel(NioDatagramChannel nioDatagramChannel) {
                nioDatagramChannel.pipeline().addLast(new DatagramDnsQueryDecoder());
                nioDatagramChannel.pipeline().addLast(new DatagramDnsResponseEncoder());
                nioDatagramChannel.pipeline().addLast(new DnsHandler(DnsServer.this, eventLoopGroup, nameServerList));
            }
        });
        bootstrap.bind(port).addListener(f -> {
            if (!f.isSuccess()) {
                log.error("Listened on port {} fail", port, f.cause());
                return;
            }
            log.info("Listened on port {}", port);
        });
    }

    @Override
    protected void freeObjects() {
        eventLoopGroup.shutdownGracefully();
    }
}
