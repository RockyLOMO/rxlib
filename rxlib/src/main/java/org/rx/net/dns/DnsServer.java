package org.rx.net.dns;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;

@Slf4j
public class DnsServer extends DnsResolverSupport {
    public static final int DEFAULT_NEGATIVE_TTL = 5;

    static final AttributeKey<DnsServer> ATTR_SVR = AttributeKey.valueOf("svr");
    static final AttributeKey<DnsClient> ATTR_UPSTREAM = AttributeKey.valueOf("upstream");
    static final AttributeKey<InetSocketAddress> ATTR_UDP_SENDER = AttributeKey.valueOf("dnsUdpSender");
    final ServerBootstrap serverBootstrap;
    final DnsClient upstreamClient;
    final List<Channel> tcpChannels;
    final List<Channel> udpChannels;
    @Getter
    volatile DnsDoHConfig dohConfig = new DnsDoHConfig();

    public DnsServer(int port) {
        this(port, null);
    }

    //AES or TLS mainly for TCP
    public DnsServer(int port, Collection<InetSocketAddress> nameServerList) {
        boolean upstreamLocalSystemFallback = DnsClient.localSystemFallback();
        if (nameServerList == null || nameServerList.isEmpty()) {
            nameServerList = DnsClient.directNameServers();
            if (nameServerList.isEmpty()) {
                upstreamLocalSystemFallback = true;
            }
        }

        upstreamClient = new DnsClient(nameServerList, upstreamLocalSystemFallback);
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

    public DnsServer enableDoH(DnsDoHConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config");
        }
        config.setEnabled(true);
        dohConfig = config;
        return this;
    }

}
