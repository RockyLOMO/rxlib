package org.rx.net.dns;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DnsServer extends Disposable {
    final NioEventLoopGroup eventLoopGroup;
    @Getter
    final Map<String, byte[]> customHosts = new ConcurrentHashMap<>(0);

    public DnsServer() {
        this(53, DnsClient.defaultNameServer());
    }

    public DnsServer(int port, InetSocketAddress... nameServerList) {
        eventLoopGroup = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup).channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, true).handler(new ChannelInitializer<NioDatagramChannel>() {
            @Override
            protected void initChannel(NioDatagramChannel nioDatagramChannel) {
                nioDatagramChannel.pipeline().addLast(new DatagramDnsQueryDecoder());
                nioDatagramChannel.pipeline().addLast(new DatagramDnsResponseEncoder());
                nioDatagramChannel.pipeline().addLast(new DnsHandler(customHosts, eventLoopGroup, nameServerList));
            }
        }).bind(port).addListener(f -> {
            if (!f.isSuccess()) {
                log.error("Listen on port {} fail", port, f.cause());
            }
        });
    }

    @Override
    protected void freeObjects() {
        eventLoopGroup.shutdownGracefully();
    }
}
