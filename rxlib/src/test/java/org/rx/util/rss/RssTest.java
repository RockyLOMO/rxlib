package org.rx.util.rss;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.local.LocalAddress;
import org.junit.jupiter.api.Disabled;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.bean.DateTime;
import org.rx.bean.RandomList;
import org.rx.bean.Tuple;
import org.rx.core.RxConfig;
import org.rx.core.Tasks;
import org.rx.core.YamlConfiguration;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.OptimalSettings;
import org.rx.net.Sockets;
import org.rx.net.TransportFlags;
import org.rx.net.dns.DnsServer;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.socks.DefaultSocksAuthenticator;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.ShadowsocksServer;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksContext;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.SocksUser;
import org.rx.net.socks.TrafficLoginInfo;
import org.rx.net.socks.encryption.CipherKind;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.transport.TcpServerConfig;
import org.rx.util.function.TripleAction;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rx.core.Sys.toJsonString;

public class RssTest extends AbstractTester {
    final int connectTimeoutMillis = 30000;
    final String socks5Usr = "rocky";
    final String socks5Pwd = "123456";
    final CopyOnWriteArraySet<String> bypassHosts = new CopyOnWriteArraySet<String>(RxConfig.INSTANCE.getNet().getBypassHosts()) {{
        add("*qq*");
    }};

    @Test
    public void resolveClientInListenAddress_DefaultToLocalAddress() {
        RSSConf conf = new RSSConf();

        SocketAddress address = RssClient.resolveClientInListenAddress(conf, 6885, "rss-in-");

        assertEquals(new LocalAddress("rss-in-6885"), address);
    }

    @Test
    public void resolveClientInListenAddress_BindPortUsesLoopback() {
        RSSConf conf = new RSSConf();
        conf.socksBindPort = true;

        SocketAddress address = RssClient.resolveClientInListenAddress(conf, 6885, "rss-in-");

        assertTrue(address instanceof InetSocketAddress);
        InetSocketAddress endpoint = (InetSocketAddress) address;
        assertEquals(6885, endpoint.getPort());
        assertNotNull(endpoint.getAddress());
        assertTrue(endpoint.getAddress().isLoopbackAddress());
    }

    @SneakyThrows
    @Test
    public void yamlConf() {
        YamlConfiguration yamlConfiguration = new YamlConfiguration("output.yaml");
        RSSConf rssConf = yamlConfiguration.readAs(null, RSSConf.class);
        System.out.println("反序列化rssConf: " + rssConf);
        System.out.println("序列化rssConf:" + yamlConfiguration.dump());
    }

    @SneakyThrows
    @Test
    public void shadowUserJson() {
        ShadowUser user = new ShadowUser();
        user.setUsername("r");
        user.setSocksUser("inner-r");
        user.setLastResetTime(DateTime.now());
        user.getLoginIps().put(java.net.InetAddress.getByName("18.12.3.4"), new TrafficLoginInfo());
        System.out.println(toJsonString(user));
    }

    @Test
    public void optimalSettings() {
        OptimalSettings[] a = {RssSupport.SS_IN_OPS, RssSupport.OUT_OPS};
        for (OptimalSettings ops : a) {
            ops.calculate();
            System.out.println(ops);
        }
    }

    @SneakyThrows
    @Disabled("manual rss integration")
    @Test
    public void tstUdp() {
        InetSocketAddress socksUdpEp = Sockets.parseEndpoint("127.0.0.1:1080");
        InetSocketAddress ntpServer = Sockets.parseEndpoint("pool.ntp.org:123");

        CountDownLatch latch = new CountDownLatch(10);
        Channel channel = Sockets.udpBootstrap(null, ob -> ob.pipeline().addLast(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
                ByteBuf buf = msg.content();
                System.out.println("readableBytes: " + buf.readableBytes() + ", hex: " + ByteBufUtil.prettyHexDump(buf));
                if (buf.readableBytes() < 48) {
                    return;
                }
                long[] result = new long[2];
                result[0] = System.currentTimeMillis();
                UnresolvedEndpoint dstEp = org.rx.net.socks.UdpManager.socks5Decode(buf);
                System.out.println("from dstEp: " + dstEp);

                buf.skipBytes(40);
                long transmitTimestamp = buf.readLong();

                long ntpSeconds = transmitTimestamp >>> 32;
                long ntpFraction = transmitTimestamp & 0xFFFFFFFFL;
                long milliseconds = (ntpSeconds * 1000) + ((ntpFraction * 1000) / 0x100000000L);
                long epochOffset = 2208988800000L;
                long serverTimeMillis = milliseconds - epochOffset;
                result[1] = serverTimeMillis;

                DateTime localTime = new DateTime(result[0]);
                DateTime serverTime = new DateTime(result[1]);
                System.out.println("NTP服务器" + ntpServer + "时间: " + serverTime);
                System.out.println("本地系统时间: " + localTime);
                latch.countDown();
            }
        })).bind(0).sync().channel();

        for (int i = 0; i < latch.getCount(); i++) {
            Tasks.run(() -> {
                ByteBuf buf = channel.alloc().directBuffer(48);
                buf.writeByte(0x1B);
                buf.writeZero(47);
                channel.writeAndFlush(new DatagramPacket(org.rx.net.socks.UdpManager.socks5Encode(buf, ntpServer), socksUdpEp));
            });
        }

        assert latch.await(20000, TimeUnit.MILLISECONDS);
    }

    @SneakyThrows
    @Disabled("manual rss integration")
    @Test
    public void ssProxy() {
        createSocksSvr(false);
        System.in.read();
    }

    void createSocksSvr(boolean udp2raw) {
        int shadowDnsPort = 853;
        int outSvrPort = 2080;
        int inSvrPort = 2090;
        int ssPort = 2092;

        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(shadowDnsPort);
        new DnsServer(shadowDnsPort);

        InetSocketAddress outSrvEp = Sockets.newLoopbackEndpoint(outSvrPort);
        SocksConfig outConf = new SocksConfig(outSrvEp.getPort());
        outConf.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());
        outConf.setConnectTimeoutMillis(connectTimeoutMillis);
        SocksUser usr = new SocksUser(socks5Usr);
        usr.setPassword(socks5Pwd);
        outConf.setEnableUdp2raw(udp2raw);
        SocksProxyServer backSvr = new SocksProxyServer(outConf, new DefaultSocksAuthenticator(Collections.singletonList(usr)));
        backSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);

        RpcServerConfig rpcServerConf = new RpcServerConfig(new TcpServerConfig(outSrvEp.getPort() + 1));
        rpcServerConf.getTcpConfig().setTransportFlags(TransportFlags.CIPHER_BOTH.flags(TransportFlags.HTTP_PSEUDO_BOTH));
        Remoting.register(new RssRpcApp(backSvr), rpcServerConf);

        RandomList<UpstreamSupport> socksServers = new RandomList<>();
        RpcClientConfig<SocksRpcContract> rpcClientConf = RpcClientConfig.poolMode(Sockets.newEndpoint(outSrvEp, outSrvEp.getPort() + 1), 2, 2);
        rpcClientConf.getTcpConfig().setTransportFlags(TransportFlags.CIPHER_BOTH.flags(TransportFlags.HTTP_PSEUDO_BOTH));
        socksServers.add(new UpstreamSupport(new AuthenticEndpoint(outSrvEp, socks5Usr, socks5Pwd), Remoting.createFacade(SocksRpcContract.class, rpcClientConf)));

        SocksConfig inConf = new SocksConfig(inSvrPort);
        inConf.setTransportFlags(TransportFlags.COMPRESS_BOTH.flags());
        inConf.setConnectTimeoutMillis(connectTimeoutMillis);
        inConf.setEnableUdp2raw(udp2raw);
        inConf.setUdp2rawClient(outSrvEp);
        SocksProxyServer inSvr = new SocksProxyServer(inConf);
        inSvr.setCipherRouter(SocksProxyServer.DNS_CIPHER_ROUTER);
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        TripleAction<SocksProxyServer, SocksContext> firstRoute = (s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (dstEp.getPort() == SocksRpcContract.DNS_PORT) {
                e.setUpstream(shadowDnsUpstream);
                return;
            }
            if (Sockets.isBypass(bypassHosts, dstEp.getHost())) {
                e.setUpstream(new Upstream(dstEp));
            }
        };
        inSvr.onTcpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            e.setUpstream(new SocksTcpUpstream(dstEp, inConf, socksServers.next()));
        });
        inSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            e.setUpstream(new SocksUdpUpstream(dstEp, inConf, socksServers.next()));
        });

        AuthenticEndpoint inSrvEp = new AuthenticEndpoint(Sockets.newLoopbackEndpoint(inSvrPort));
        ShadowsocksConfig frontConf = new ShadowsocksConfig(Sockets.newAnyEndpoint(ssPort),
                CipherKind.AES_128_GCM.getCipherName(), socks5Pwd);
        ShadowsocksServer frontSvr = new ShadowsocksServer(frontConf);
        frontSvr.onTcpRoute.replace((s, e) ->
                e.setUpstream(new SocksTcpUpstream(e.getFirstDestination(), inConf, new UpstreamSupport(inSrvEp, null))));
        frontSvr.onUdpRoute.replace((s, e) ->
                e.setUpstream(new SocksUdpUpstream(e.getFirstDestination(), inConf, new UpstreamSupport(inSrvEp, null))));
    }
}
