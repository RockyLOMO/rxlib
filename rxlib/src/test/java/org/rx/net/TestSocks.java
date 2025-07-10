package org.rx.net;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http.multipart.FileUpload;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.Main;
import org.rx.bean.LogStrategy;
import org.rx.bean.MultiValueMap;
import org.rx.bean.RandomList;
import org.rx.bean.ULID;
import org.rx.codec.AESUtil;
import org.rx.codec.CodecUtil;
import org.rx.codec.XChaCha20Poly1305Util;
import org.rx.core.Arrays;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;
import org.rx.io.Files;
import org.rx.io.HybridStream;
import org.rx.io.IOStream;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpClient;
import org.rx.net.http.HttpServer;
import org.rx.net.http.RestClient;
import org.rx.net.nameserver.NameserverClient;
import org.rx.net.nameserver.NameserverConfig;
import org.rx.net.nameserver.NameserverImpl;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingException;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.shadowsocks.ShadowsocksConfig;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.shadowsocks.encryption.CipherKind;
import org.rx.net.socks.*;
import org.rx.net.socks.upstream.Socks5UdpUpstream;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.IPSearcher;
import org.rx.net.support.SocksSupport;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.net.support.UpstreamSupport;
import org.rx.net.transport.SftpClient;
import org.rx.net.transport.TcpServer;
import org.rx.net.transport.TcpServerConfig;
import org.rx.net.transport.UdpClient;
import org.rx.test.*;
import org.rx.third.apache.ntp.*;
import org.rx.util.function.TripleAction;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.sleep;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public class TestSocks extends AbstractTester {
    final Map<Object, TcpServer> serverHost = new ConcurrentHashMap<>();
    final long startDelay = 4000;
    final String eventName = "onCallback";

    //region ns
    final NameserverConfig conf1 = new NameserverConfig() {{
        setDnsPort(1853);
        setRegisterPort(1854);
    }};
    final NameserverImpl ns1 = new NameserverImpl(conf1);
    final NameserverConfig conf2 = new NameserverConfig() {{
        setDnsPort(1953);
        setRegisterPort(1954);
    }};
    final NameserverImpl ns2 = new NameserverImpl(conf2);
    final String appUsercenter = "usercenter";
    final String appOrder = "order";
    final String node1 = String.format("127.0.0.1:%s", conf1.getRegisterPort());
    final String node2 = String.format("127.0.0.1:%s", conf2.getRegisterPort());

    @Test
    public void singleNode() {
        NameserverClient c1 = new NameserverClient(appUsercenter);
        NameserverClient c2 = new NameserverClient(appOrder);

        c1.registerAsync(node1).join();
        List<InetAddress> discover = c1.discover(appUsercenter);
        System.out.println(toJsonString(discover));
        assert discover.contains(Sockets.getLoopbackAddress());

        discover = c1.discover(appOrder);
        assert CollectionUtils.isEmpty(discover);

        c2.registerAsync(node1).join();

        discover = c1.discover(appOrder);
        assert discover.contains(Sockets.getLoopbackAddress());

        c2.deregisterAsync().join();
        discover = c1.discover(appOrder);
        assert CollectionUtils.isEmpty(discover);
    }

    @Test
    public void multiNode1() {
        NameserverClient c1 = new NameserverClient(appUsercenter);
        NameserverClient c2 = new NameserverClient(appOrder);

        c1.registerAsync(node1).join();
        c2.registerAsync(node2, node1).join();
        sleep4Sync();

        System.out.println("c1: " + toJsonString(c1.registerEndpoints()));
        assert c1.registerEndpoints().containsAll(c2.registerEndpoints());

        c2.deregisterAsync().join();
        sleep4Sync();
        List<InetAddress> discover = c1.discover(appOrder);
        assert CollectionUtils.isEmpty(discover);
    }

    @Test
    public void multiNode2() {
        NameserverClient c1 = new NameserverClient(appUsercenter);
        NameserverClient c2 = new NameserverClient(appOrder);

        c1.registerAsync(node1).join();
        c2.registerAsync(node2).join();
        assert !c1.registerEndpoints().containsAll(c2.registerEndpoints());

        ns2.syncRegister(c1.registerEndpoints());

        sleep4Sync();

        System.out.println(toJsonString(c1.registerEndpoints()));
        assert c1.registerEndpoints().containsAll(c2.registerEndpoints());
    }

    @SneakyThrows
    @Test
    public void singleClient() {
        NameserverClient c1 = new NameserverClient(appUsercenter);

        c1.registerAsync(node1, node2);
        c1.waitInject();

        sleep4Sync();
        System.out.println("ns1:" + ns1.getDnsServer().getHosts());
        System.out.println("ns2:" + ns2.getDnsServer().getHosts());
    }

    private void sleep4Sync() {
        sleep(5000);
        System.out.println("-等待异步同步-");
    }
    //endregion

    @ChannelHandler.Sharable
    static class MultiCastInboundHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
            log.info("recv: {} - {}", ctx.channel().id(), packet);
        }
    }

    @SneakyThrows
    @Test
    public void multiCastUdp() {
        for (Inet4Address address : Sockets.getLocalAddresses(false)) {
            System.out.println(address);
        }

        InetSocketAddress multiCastEp = Sockets.parseEndpoint("239.0.0.1:8888");
        NioDatagramChannel c1, c2, c3;
        MultiCastInboundHandler inHandler = new MultiCastInboundHandler();
        c1 = (NioDatagramChannel) Sockets.udpBootstrap(MemoryMode.LOW, true, c -> c.pipeline().addLast(inHandler))
                .bind(multiCastEp.getPort()).addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        log.info("multicast bind error", f.cause());
                        return;
                    }
                    NioDatagramChannel c = (NioDatagramChannel) f.channel();
                    c.joinGroup(multiCastEp.getAddress());
                    log.info("multicast join {}", multiCastEp);
                }).channel();

        c2 = (NioDatagramChannel) Sockets.udpBootstrap(MemoryMode.LOW, true, c -> {
                    c.pipeline().addLast(inHandler);
                    log.info("c2 inHandler");
                })
                .bind(multiCastEp.getPort()).addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        log.info("multicast bind error", f.cause());
                        return;
                    }
                    NioDatagramChannel c = (NioDatagramChannel) f.channel();
                    c.joinGroup(multiCastEp.getAddress());
                    log.info("multicast join {}", multiCastEp);
                }).channel();

        c3 = (NioDatagramChannel) Sockets.udpBootstrap(MemoryMode.LOW, false, c -> c.pipeline().addLast(inHandler))
                .bind(0).channel();

        Tasks.setTimeout(() -> {
            ByteBuf buf = Bytes.directBuffer();
            buf.writeCharSequence("hello", StandardCharsets.UTF_8);
            DatagramPacket pack = new DatagramPacket(buf, multiCastEp);
            c1.writeAndFlush(pack);
            log.info("c1 send: {}", pack);
        }, 4000);

        Tasks.setTimeout(() -> {
            ByteBuf buf = Bytes.directBuffer();
            buf.writeCharSequence("hello", StandardCharsets.UTF_8);
            DatagramPacket pack = new DatagramPacket(buf, multiCastEp);
            c1.writeAndFlush(pack);
            log.info("c3 send: {}", pack);
        }, 5000);

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void netWait() {
        AtomicInteger c = new AtomicInteger();
        String g1 = "g1", g2 = "g2";
        NetEventWait wait1 = new NetEventWait(g1),
                wait2 = new NetEventWait(g1),
                wait3 = new NetEventWait(g2);

        Tasks.run(() -> {
            boolean ok = wait1.await();
            c.incrementAndGet();
            log.info("wait1 {}", ok);
        });
        Tasks.run(() -> {
            boolean ok = wait2.await();
            c.incrementAndGet();
            log.info("wait2 {}", ok);
        });
        Tasks.run(() -> {
            boolean ok = wait3.await(2000, 500, i -> {
                log.info("segment {}", 1);
                return i == 3;
            });
            log.info("wait3 {}", ok);
        });

        Tasks.setTimeout(() -> {
            wait2.setMulticastCount(2);
            wait2.signalAll();
            log.info("wait2 signalAll");
        }, 4000);

        FluentWait.polling(10000, 1000).awaitTrue(w -> c.get() == 2);
    }

    //region rpc
    @Test
    public void rpcStatefulApi() {
        UserManagerImpl svcImpl = new UserManagerImpl();
        startServer(svcImpl, endpoint_3307);

        List<UserManager> facadeGroup = new ArrayList<>();
        facadeGroup.add(Remoting.createFacade(UserManager.class, RpcClientConfig.statefulMode(endpoint_3307, 0)));
        facadeGroup.add(Remoting.createFacade(UserManager.class, RpcClientConfig.statefulMode(endpoint_3307, 0)));

        rpcApiEvent(svcImpl, facadeGroup);
        //重启server，客户端自动重连
        restartServer(svcImpl, endpoint_3307, startDelay);
        rpcApiEvent(svcImpl, facadeGroup);

//        facadeGroup.get(0).close();  //facade接口继承AutoCloseable调用后可主动关闭连接
    }

    private void rpcApiEvent(UserManagerImpl svcImpl, List<UserManager> facadeGroup) {
        for (UserManager facade : facadeGroup) {
            try {
                facade.triggerError();
            } catch (RemotingException e) {
            }
            assert facade.computeLevel(1, 1) == 2;  //服务端计算并返回
        }

        //自定义事件（广播） 只加1次
        for (int i = 0; i < facadeGroup.size(); i++) {
            int x = i;
            facadeGroup.get(i).<UserEventArgs>attachEvent(eventName, (s, e) -> {
                log.info("facade{} {} -> args.flag={}", x, eventName, e.getFlag());
                e.setFlag(e.getFlag() + 1);
            }, false);
        }
        for (int i = 0; i < 1; i++) {
            UserEventArgs args = new UserEventArgs(PersonBean.LeZhi);
            facadeGroup.get(0).raiseEvent(eventName, args);
            log.info("facade0 flag:" + args.getFlag());
            assert args.getFlag() == 1;

            args = new UserEventArgs(PersonBean.LeZhi);
            args.setFlag(1);
            facadeGroup.get(1).raiseEvent(eventName, args);
            log.info("facade1 flag:" + args.getFlag());
            assert args.getFlag() == 2;

            svcImpl.raiseEvent(eventName, args);
            sleep(50);
            log.info("svr flag:" + args.getFlag());
            //开启计算广播是3，没开启是2
            assert args.getFlag() == 2;  //服务端触发事件，先执行最后一次注册事件，拿到最后一次注册客户端的EventArgs值，再广播其它组内客户端。
        }
    }

    @Test
    public void rpcStatefulImpl() {
        //服务端监听
        RpcServerConfig serverConfig = new RpcServerConfig(new TcpServerConfig(3307));
        serverConfig.setEventComputeVersion(2);  //版本号一样的才会去client计算eventArgs再广播
//        serverConfig.getEventBroadcastVersions().add(2);  //版本号一致才广播
        UserManagerImpl server = new UserManagerImpl();
        Remoting.register(server, serverConfig);

        //客户端 facade
        RpcClientConfig<UserManagerImpl> config = RpcClientConfig.statefulMode("127.0.0.1:3307", 1);
        config.setInitHandler((p, c) -> {
            System.out.println("onHandshake: " + p.computeLevel(1, 2));
        });
        UserManagerImpl facade1 = Remoting.createFacade(UserManagerImpl.class, config);
        assert facade1.computeLevel(1, 1) == 2; //服务端计算并返回
        try {
            facade1.triggerError(); //测试异常
        } catch (RemotingException e) {
        }
        assert facade1.computeLevel(17, 1) == 18;

        config.setEventVersion(2);
        UserManagerImpl facade2 = Remoting.createFacade(UserManagerImpl.class, config);
        //注册事件（广播）
        rpcImplEvent(facade1, "0x00");
        //服务端触发事件，只有facade1注册会被广播到
        server.create(PersonBean.LeZhi);

        rpcImplEvent(facade2, "0x01");
        //服务端触发事件，facade1,facade2随机触发计算eventArgs，然后用计算出的eventArgs广播非计算的facade
        server.create(PersonBean.LeZhi);

        //客户端触发事件
        facade1.create(PersonBean.LeZhi);
        sleep(5000);
    }

    private void rpcImplEvent(UserManagerImpl facade, String id) {
        facade.<UserEventArgs>attachEvent("onCreate", (s, e) -> {
//          Tasks.run(()->  facade.computeInt(0, -1));
            System.out.println("onInnerCall start");
            assert facade.computeLevel(0, -1) == -1;
            System.out.println("onInnerCall end");
            log.info("facade{} onCreate -> {}", id, toJsonString(e));
            e.getStatefulList().add(id + ":" + ULID.randomULID());
            e.setCancel(false); //是否取消事件
        });
    }

    @Test
    public void rpcReconnect() {
        UserManagerImpl svcImpl = new UserManagerImpl();
        startServer(svcImpl, endpoint_3307);
        AtomicBoolean connected = new AtomicBoolean(false);
        RpcClientConfig<UserManager> config = RpcClientConfig.statefulMode(endpoint_3307, 0);
        config.setInitHandler((p, c) -> {
            p.attachEvent(eventName, (s, e) -> {
                System.out.println("attachEvent callback");
            }, false);
            System.out.println("attachEvent done");

            c.onReconnecting.combine((s, e) -> {
                InetSocketAddress next;
                if (eq(e.getValue().getPort(), endpoint_3307.getPort())) {
                    next = endpoint_3308;
                } else {
                    next = endpoint_3307;  //3307和3308端口轮询重试连接，模拟分布式不同端口重试连接
                }
                System.out.println("reconnect " + next);
                e.setValue(next);
            });
            log.debug("init ok");
            connected.set(true);
        });
        UserManager userManager = Remoting.createFacade(UserManager.class, config);
        assert userManager.computeLevel(1, 1) == 2;
        userManager.raiseEvent(eventName, EventArgs.EMPTY);

        restartServer(svcImpl, endpoint_3308, startDelay); //10秒后开启3308端口实例，重连3308成功
        int max = 10;
        for (int i = 0; i < max; ) {
            if (!connected.get()) {
                sleep(1000);
                continue;
            }
            if (i == 0) {
                sleep(5000);
                log.debug("sleep 5");
            }
            assert userManager.computeLevel(i, 1) == i + 1;
            i++;
        }
        userManager.raiseEvent(eventName, EventArgs.EMPTY);
        userManager.raiseEvent(eventName, EventArgs.EMPTY);

        sleep(5000);
    }

    <T> void startServer(T svcImpl, InetSocketAddress ep) {
        RpcServerConfig svr = new RpcServerConfig(new TcpServerConfig(ep.getPort()));
        svr.getTcpConfig().setUseSharedTcpEventLoop(false);
        serverHost.computeIfAbsent(svcImpl, k -> Remoting.register(k, svr));
        System.out.println("Start server on port " + ep.getPort());
    }

    <T> Future<?> restartServer(T svcImpl, InetSocketAddress ep, long startDelay) {
        TcpServer rs = serverHost.remove(svcImpl);
        sleep(startDelay);
        rs.close();
        System.out.println("Close server on port " + rs.getConfig().getListenPort());
        return Tasks.setTimeout(() -> startServer(svcImpl, ep), startDelay);
    }

    @SneakyThrows
    @Test
    public void rpcPoolMode() {
        Remoting.register(HttpUserManager.INSTANCE, endpoint_3307.getPort(), true);

        int tcount = 200;
        CountDownLatch latch = new CountDownLatch(tcount);
        //没有事件订阅，无状态会使用连接池模式
        int threadCount = 8;
        HttpUserManager facade = Remoting.createFacade(HttpUserManager.class, RpcClientConfig.poolMode(endpoint_3307, 1, threadCount));
        for (int i = 0; i < tcount; i++) {
            int finalI = i;
            Tasks.run(() -> {
                facade.computeLevel(1, finalI);
                sleep(1000);
                latch.countDown();
            });
        }
        latch.await();
    }

    @SneakyThrows
    @Test
    public void udpRpc() {
        UdpClient c1 = new UdpClient(endpoint_3307.getPort());
        c1.onReceive.combine((s, e) -> log.info("client1 recv {}", toJsonString(e)));
        UdpClient c2 = new UdpClient(endpoint_3308.getPort());
        AtomicInteger count = new AtomicInteger();
        c2.onReceive.combine((s, e) -> {
            log.info("client2 recv {}", toJsonString(e));
            if (count.incrementAndGet() < 2) {
                throw new InvalidException("error");
            }
        });

        c1.sendAsync(endpoint_3307, "我是1");
        for (int i = 0; i < 10; i++) {
            int finalI = i;
            Tasks.run(() -> c2.sendAsync(endpoint_3307, "我是2 + " + finalI));
        }

        c1.sendAsync(endpoint_3308, str_name_wyf, 15000, true);
        System.out.println("done");
        _wait();
    }
    //endregion

    //region ss
    int connectTimeoutMillis = 30000;
    String socks5Usr = "rocky";
    String socks5Pwd = "123456";

    @SneakyThrows
    @Test
    public void ssProxy() {
        //dns
        int shadowDnsPort = 853;
        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(shadowDnsPort);
        DnsServer dnsSvr = new DnsServer(shadowDnsPort);

        //backend
        InetSocketAddress backSrvEp = Sockets.newLoopbackEndpoint(2080);
        SocksConfig backConf = new SocksConfig(backSrvEp.getPort());
        backConf.setConnectTimeoutMillis(connectTimeoutMillis);
        SocksUser usr = new SocksUser(socks5Usr);
        usr.setPassword(socks5Pwd);
        usr.setMaxIpCount(-1);
        SocksProxyServer backSvr = new SocksProxyServer(backConf, new DefaultSocksAuthenticator(Collections.singletonList(usr)));

        RpcServerConfig rpcServerConf = new RpcServerConfig(new TcpServerConfig(backSrvEp.getPort() + 1));
        rpcServerConf.getTcpConfig().setTransportFlags(TransportFlags.SERVER_COMPRESS_BOTH.flags());
        Remoting.register(new Main(backSvr), rpcServerConf);

        //frontend
        RandomList<UpstreamSupport> shadowServers = new RandomList<>();
        RpcClientConfig<SocksSupport> rpcClientConf = RpcClientConfig.poolMode(Sockets.newEndpoint(backSrvEp, backSrvEp.getPort() + 1), 2, 2);
        rpcClientConf.getTcpConfig().setTransportFlags(TransportFlags.CLIENT_COMPRESS_BOTH.flags());
        shadowServers.add(new UpstreamSupport(new AuthenticEndpoint(backSrvEp), Remoting.createFacade(SocksSupport.class, rpcClientConf)));

        AuthenticEndpoint srvEp = new AuthenticEndpoint(backSrvEp, usr.getUsername(), usr.getPassword());
        ShadowsocksConfig frontConf = new ShadowsocksConfig(Sockets.newAnyEndpoint(2090),
                CipherKind.AES_128_GCM.getCipherName(), socks5Pwd);
        ShadowsocksServer frontSvr = new ShadowsocksServer(frontConf);
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        TripleAction<ShadowsocksServer, SocksContext> firstRoute = (s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            //must first
            if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                e.setUpstream(shadowDnsUpstream);
                return;
            }
            //bypass
            if (Sockets.isBypass(frontConf.getBypassHosts(), dstEp.getHost())) {
                e.setUpstream(new Upstream(dstEp));
            }
        };
        frontSvr.onRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            e.setUpstream(new Socks5Upstream(e.getFirstDestination(), backConf, () -> new UpstreamSupport(srvEp, null)));
//            e.setUpstream(new Socks5Upstream(e.getFirstDestination(), backConf, shadowServers::next));
        });
        frontSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            e.setUpstream(new Upstream(e.getFirstDestination(), srvEp));
//            e.setUpstream(new Upstream(e.getFirstDestination()));
        });

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void socks5Proxy() {
        boolean udp2raw = false;
        boolean udp2rawDirect = false;
        Udp2rawHandler.DEFAULT.setGzipMinLength(40);

        //dns
        int shadowDnsPort = 853;
        InetSocketAddress shadowDnsEp = Sockets.newLoopbackEndpoint(shadowDnsPort);
        DnsServer dnsSvr = new DnsServer(shadowDnsPort);
//        Sockets.injectNameService(shadowDnsEp);

        //backend
        InetSocketAddress backSrvEp = Sockets.newLoopbackEndpoint(2080);
        SocksConfig backConf = new SocksConfig(backSrvEp.getPort());
        backConf.setTransportFlags(TransportFlags.SERVER_COMPRESS_BOTH.flags());
        backConf.setConnectTimeoutMillis(connectTimeoutMillis);
        backConf.setEnableUdp2raw(udp2raw);
        SocksProxyServer backSvr = new SocksProxyServer(backConf, null);
//        backSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

        RpcServerConfig rpcServerConf = new RpcServerConfig(new TcpServerConfig(backSrvEp.getPort() + 1));
        rpcServerConf.getTcpConfig().setTransportFlags(TransportFlags.SERVER_CIPHER_BOTH.flags(TransportFlags.SERVER_HTTP_PSEUDO_BOTH));
        Remoting.register(new Main(backSvr), rpcServerConf);

        //frontend
        RandomList<UpstreamSupport> shadowServers = new RandomList<>();
        RpcClientConfig<SocksSupport> rpcClientConf = RpcClientConfig.poolMode(Sockets.newEndpoint(backSrvEp, backSrvEp.getPort() + 1), 2, 2);
        rpcClientConf.getTcpConfig().setTransportFlags(TransportFlags.CLIENT_CIPHER_BOTH.flags(TransportFlags.CLIENT_HTTP_PSEUDO_BOTH));
        shadowServers.add(new UpstreamSupport(new AuthenticEndpoint(backSrvEp), Remoting.createFacade(SocksSupport.class, rpcClientConf)));

        SocksConfig frontConf = new SocksConfig(2090);
        frontConf.setTransportFlags(TransportFlags.CLIENT_COMPRESS_BOTH.flags());
        frontConf.setConnectTimeoutMillis(connectTimeoutMillis);
        frontConf.setEnableUdp2raw(udp2raw);
        if (!udp2rawDirect) {
            frontConf.setUdp2rawServers(Arrays.toList(backSrvEp));
        } else {
            frontConf.setUdp2rawServers(Collections.emptyList());
        }
        SocksProxyServer frontSvr = new SocksProxyServer(frontConf);
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        TripleAction<SocksProxyServer, SocksContext> firstRoute = (s, e) -> {
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            //must first
            if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                e.setUpstream(shadowDnsUpstream);
                return;
            }
            //bypass
            if (Sockets.isBypass(frontConf.getBypassHosts(), dstEp.getHost())) {
                e.setUpstream(new Upstream(dstEp));
            }
        };
        frontSvr.onRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            e.setUpstream(new Socks5Upstream(e.getFirstDestination(), frontConf, shadowServers::next));
        });
        frontSvr.onUdpRoute.replace(firstRoute, (s, e) -> {
            if (e.getUpstream() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getFirstDestination();
            if (frontConf.isEnableUdp2raw()) {
                if (!udp2rawDirect) {
                    e.setUpstream(new Upstream(dstEp, shadowServers.next().getEndpoint()));
                } else {
                    e.setUpstream(new Upstream(dstEp));
                }
                return;
            }
            e.setUpstream(new Socks5UdpUpstream(dstEp, frontConf, shadowServers::next));
        });
//        frontSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

        System.in.read();
    }

    @Test
    public void testXChaCha() {
        // Generate a key
        byte[] key = XChaCha20Poly1305Util.generateKey();
        System.out.println("Key: " + CodecUtil.convertToBase64(key));

        // Encrypt
        String plaintext = "Hello, this is a test message for XChaCha20-Poly1305!";
        byte[] encrypted = XChaCha20Poly1305Util.encrypt(key, plaintext.getBytes(StandardCharsets.UTF_8));
        System.out.println("Encrypted: " + encrypted);

        // Decrypt
        byte[] decrypted = XChaCha20Poly1305Util.decrypt(key, encrypted);
        System.out.println("Decrypted: " + new String(decrypted));
    }

//    @SneakyThrows
//    @Test
//    public void directProxy() {
//        DirectConfig frontConf = new DirectConfig(3307);
//        frontConf.setTransportFlags(TransportFlags.BACKEND_AES_COMBO.flags());
//        DirectProxyServer frontSvr = new DirectProxyServer(frontConf, p -> Sockets.parseEndpoint("127.0.0.1:3308"));
//
//        DirectConfig backConf = new DirectConfig(3308);
//        backConf.setTransportFlags(TransportFlags.FRONTEND_AES_COMBO.flags());
//        DirectProxyServer backSvr = new DirectProxyServer(backConf, p -> Sockets.parseEndpoint("rm-bp1hddend5q83p03g674.mysql.rds.aliyuncs.com:3306"));
//
//        System.in.read();
//    }
    //endregion

    //region rrp
    @SneakyThrows
    @Test
    public void rrp() {
        System.setProperty("io.netty.leakDetectionLevel", "ADVANCED");
        //客户端服务端配置需要分开
        RrpConfig sConf = new RrpConfig();
        sConf.setToken("youfanX");
        sConf.setBindPort(9000);
        RrpServer server = new RrpServer(sConf);

        RrpConfig cConf = new RrpConfig();
        cConf.setToken("youfanX");
        cConf.setServerEndpoint("127.0.0.1:9000");
//        c.setServerEndpoint("cloud.f-li.cn:4001");
        RrpConfig.Proxy p = new RrpConfig.Proxy();
        p.setName("ss");
//        p.setType(1);
        p.setRemotePort(2090);
        p.setAuth("lezhi:lezhi2020");
        cConf.setProxies(Collections.singletonList(p));
        log.info("client conf {}", toJsonString(Collections.singletonMap("k", toJsonString(cConf))));

        RrpClient client = new RrpClient(cConf);
        client.connectAsync();

        System.in.read();
    }
    //endregion

    @SneakyThrows
    @Test
    public void ssh() {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setPort(22);
        sshd.setPasswordAuthenticator(AcceptAllPasswordAuthenticator.INSTANCE);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get("hostkey.ser")));
//        sshd.setPublickeyAuthenticator(new DefaultAuthorizedKeysAuthenticator(false));
        sshd.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);

        sshd.setShellFactory(InteractiveProcessShellFactory.INSTANCE);
        sshd.setFileSystemFactory(NativeFileSystemFactory.INSTANCE);
        sshd.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
        sshd.setCommandFactory(new ScpCommandFactory());

////        CloseableExecutorService e;
////        ThreadUtils.noClose();
////        EndlessTarpitSenderSupportDevelopment e;
        sshd.start();

        _wait();
    }

    @SneakyThrows
    @Test
    public void dns() {
        String host_devops = "";
        InetSocketAddress nsEp = Sockets.parseEndpoint("114.114.114.114:53");
        InetSocketAddress localNsEp = Sockets.parseEndpoint("127.0.0.1:853");

        final InetAddress ip2 = InetAddress.getByName("2.2.2.2");
        final InetAddress ip4 = InetAddress.getByName("4.4.4.4");
        final InetAddress aopIp = InetAddress.getByName("1.2.3.4");
        DnsServer server = new DnsServer(localNsEp.getPort(), Collections.singletonList(nsEp));
//        DnsServer server = new DnsServer(localNsEp.getPort());
        server.setInterceptors(new RandomList<>(Collections.singletonList(new SocksSupport() {
            @Override
            public void fakeEndpoint(BigInteger hash, String realEndpoint) {

            }

            @SneakyThrows
            @Override
            public List<InetAddress> resolveHost(String host) {
                log.info("resolveHost {}", host);
                return Collections.singletonList(aopIp);
//                return DnsClient.inlandClient().resolveAll(host);
            }

            @Override
            public void addWhiteList(InetAddress endpoint) {

            }
        })));
        server.setHostsTtl(5);
        server.setEnableHostsWeight(false);
        server.addHosts(host_devops, 2, Arrays.toList(ip2, ip4));

        //hostTtl
        DnsClient client = new DnsClient(Collections.singletonList(localNsEp));
        List<InetAddress> result = client.resolveAll(host_devops);
        System.out.println("eq: " + result);
        assert result.contains(ip2) && result.contains(ip4);
        Tasks.setTimeout(() -> {
            server.removeHosts(host_devops, Collections.singletonList(ip2));

            List<InetAddress> x = client.resolveAll(host_devops);
            System.out.println(toJsonString(x));
            assert x.contains(ip4);
            _notify();
        }, 6000);

        DnsClient inlandClient = DnsClient.inlandClient();
        InetAddress wanIp = InetAddress.getByName(IPSearcher.DEFAULT.getPublicIp());
        List<InetAddress> currentIps = inlandClient.resolveAll(host_devops);
        System.out.println("ddns: " + wanIp + " = " + currentIps);
        //注入InetAddress.getAllByName()变更要查询的dnsServer的地址，支持非53端口
        Sockets.injectNameService(Collections.singletonList(localNsEp));

        List<InetAddress> wanResult = inlandClient.resolveAll(host_devops);
        InetAddress[] localResult = InetAddress.getAllByName(host_devops);
        System.out.println("wanResolve: " + wanResult + " != " + toJsonString(localResult));
        assert !wanResult.get(0).equals(localResult[0]);

        server.addHostsFile(path("hosts.txt"));
        assert client.resolve(host_cloud).equals(InetAddress.getByName("192.168.31.7"));

        assert client.resolve("www.baidu.com").equals(aopIp);

        _wait();
    }

    @SneakyThrows
    @Test
    public void ntp() {
        String[] servers = {"ntp.aliyun.com", "ntp.tencent.com", "cn.pool.ntp.org"};
        NTPUDPClient client = new NTPUDPClient();
        client.setDefaultTimeout(10000);
        client.open();
        for (final String server : servers) {
            System.out.println();
            final InetAddress hostAddr = InetAddress.getByName(server);
            System.out.println("> " + hostAddr.getHostName() + "/" + hostAddr.getHostAddress());
            final TimeInfo info = client.getTime(hostAddr);
            processResponse(info);
        }
        client.close();
    }

    /**
     * Process <code>TimeInfo</code> object and print its details.
     *
     * @param info <code>TimeInfo</code> object.
     */
    public static void processResponse(final TimeInfo info) {
        final NumberFormat numberFormat = new java.text.DecimalFormat("0.00");
        final NtpV3Packet message = info.getMessage();
        final int stratum = message.getStratum();
        final String refType;
        if (stratum <= 0) {
            refType = "(Unspecified or Unavailable)";
        } else if (stratum == 1) {
            refType = "(Primary Reference; e.g., GPS)"; // GPS, radio clock, etc.
        } else {
            refType = "(Secondary Reference; e.g. via NTP or SNTP)";
        }
        // stratum should be 0..15...
        System.out.println(" Stratum: " + stratum + " " + refType);
        final int version = message.getVersion();
        final int li = message.getLeapIndicator();
        System.out.println(" leap=" + li + ", version="
                + version + ", precision=" + message.getPrecision());

        System.out.println(" mode: " + message.getModeName() + " (" + message.getMode() + ")");
        final int poll = message.getPoll();
        // poll value typically btwn MINPOLL (4) and MAXPOLL (14)
        System.out.println(" poll: " + (poll <= 0 ? 1 : (int) Math.pow(2, poll))
                + " seconds" + " (2 ** " + poll + ")");
        final double disp = message.getRootDispersionInMillisDouble();
        System.out.println(" rootdelay=" + numberFormat.format(message.getRootDelayInMillisDouble())
                + ", rootdispersion(ms): " + numberFormat.format(disp));

        final int refId = message.getReferenceId();
        String refAddr = NtpUtils.getHostAddress(refId);
        String refName = null;
        if (refId != 0) {
            if (refAddr.equals("127.127.1.0")) {
                refName = "LOCAL"; // This is the ref address for the Local Clock
            } else if (stratum >= 2) {
                // If reference id has 127.127 prefix then it uses its own reference clock
                // defined in the form 127.127.clock-type.unit-num (e.g. 127.127.8.0 mode 5
                // for GENERIC DCF77 AM; see refclock.htm from the NTP software distribution.
                if (!refAddr.startsWith("127.127")) {
                    try {
                        final InetAddress addr = InetAddress.getByName(refAddr);
                        final String name = addr.getHostName();
                        if (name != null && !name.equals(refAddr)) {
                            refName = name;
                        }
                    } catch (final UnknownHostException e) {
                        // some stratum-2 servers sync to ref clock device but fudge stratum level higher... (e.g. 2)
                        // ref not valid host maybe it's a reference clock name?
                        // otherwise just show the ref IP address.
                        refName = NtpUtils.getReferenceClock(message);
                    }
                }
            } else if (version >= 3 && (stratum == 0 || stratum == 1)) {
                refName = NtpUtils.getReferenceClock(message);
                // refname usually have at least 3 characters (e.g. GPS, WWV, LCL, etc.)
            }
            // otherwise give up on naming the beast...
        }
        if (refName != null && refName.length() > 1) {
            refAddr += " (" + refName + ")";
        }
        System.out.println(" Reference Identifier:\t" + refAddr);

        final TimeStamp refNtpTime = message.getReferenceTimeStamp();
        System.out.println(" Reference Timestamp:\t" + refNtpTime + "  " + refNtpTime.toDateString());

        // Originate Time is time request sent by client (t1)
        final TimeStamp origNtpTime = message.getOriginateTimeStamp();
        System.out.println(" Originate Timestamp:\t" + origNtpTime + "  " + origNtpTime.toDateString());

        final long destTimeMillis = info.getReturnTime();
        // Receive Time is time request received by server (t2)
        final TimeStamp rcvNtpTime = message.getReceiveTimeStamp();
        System.out.println(" Receive Timestamp:\t" + rcvNtpTime + "  " + rcvNtpTime.toDateString());

        // Transmit time is time reply sent by server (t3)
        final TimeStamp xmitNtpTime = message.getTransmitTimeStamp();
        System.out.println(" Transmit Timestamp:\t" + xmitNtpTime + "  " + xmitNtpTime.toDateString());

        // Destination time is time reply received by client (t4)
        final TimeStamp destNtpTime = TimeStamp.getNtpTime(destTimeMillis);
        System.out.println(" Destination Timestamp:\t" + destNtpTime + "  " + destNtpTime.toDateString());

        info.computeDetails(); // compute offset/delay if not already done
        final Long offsetMillis = info.getOffset();
        final Long delayMillis = info.getDelay();
        final String delay = delayMillis == null ? "N/A" : delayMillis.toString();
        final String offset = offsetMillis == null ? "N/A" : offsetMillis.toString();

        System.out.println(" Roundtrip delay(ms)=" + delay
                + ", clock offset(ms)=" + offset); // offset in ms
    }

    @Test
    public void ipUtil() {
        String expr = RxConfig.INSTANCE.getNet().getBypassHosts().get(3);
        assert Pattern.matches(expr, "192.168.31.7");

        SocketConfig conf = new SocketConfig();
        assert Sockets.isBypass(conf.getBypassHosts(), "127.0.0.1");
        assert Sockets.isBypass(conf.getBypassHosts(), "192.168.31.1");
        assert !Sockets.isBypass(conf.getBypassHosts(), "192.169.31.1");
        assert Sockets.isBypass(conf.getBypassHosts(), "localhost");
        assert !Sockets.isBypass(conf.getBypassHosts(), "google.cn");
        assert !Sockets.isBypass(Arrays.toList("*google.com"), "google.cn");
        assert Sockets.isBypass(Arrays.toList("*google.com"), "google.com");
        assert Sockets.isBypass(Arrays.toList("*google.com"), "rx.google.com");

        System.out.println(IPSearcher.DEFAULT.resolve("8.8.8.8"));
        System.out.println(IPSearcher.DEFAULT.resolve("61.169.146.210"));
        System.out.println(IPSearcher.DEFAULT.resolve("192.168.31.2"));
        System.out.println(IPSearcher.DEFAULT.getPublicIp());

//        String h = "google.com";
//        System.out.println(IPSearcher.DEFAULT.search(h));
//        System.out.println(IPSearcher.DEFAULT.search(h, true));
//        List<BiFunc<String, IPAddress>> apis = Reflects.readField(IPSearcher.DEFAULT, "apis");
//        BiAction<String> fn = p -> {
//            IPAddress last = null;
//            for (BiFunc<String, IPAddress> api : apis) {
//                IPAddress cur = api.invoke(p);
//                System.out.println(cur);
//                if (last == null) {
//                    last = cur;
//                    continue;
//                }
//                assert last.getIp().equals(cur.getIp())
////                        && last.getCountryCode().equals(cur.getCountryCode())
//                        ;
//                last = cur;
//            }
//        };
//        fn.invoke(Sockets.loopbackAddress().getHostAddress());
    }

    @SneakyThrows
    @Test
    public void httpServer() {
        Map<String, Object> qs = new HashMap<>();
        qs.put("a", "1");
        qs.put("b", "乐之");

        Map<String, Object> f = new HashMap<>();
        f.put("a", "1");
        f.put("b", "乐之");

        Map<String, IOStream> fi = new HashMap<>();
        fi.put("a", IOStream.wrap("1.dat", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));

        String j = "{\"a\":1,\"b\":\"乐之\"}";

        String hbody = "<html><body>hello world</body></html>";
        String jbody = "{\"code\":0,\"msg\":\"hello world\"}";

        HttpServer server = new HttpServer(8081, true);
        server.requestMapping("/api", (request, response) -> {
            MultiValueMap<String, String> queryString = request.getQueryString();
            for (Map.Entry<String, Object> entry : qs.entrySet()) {
                assert entry.getValue().equals(queryString.getFirst(entry.getKey()));
            }

            MultiValueMap<String, String> form = request.getForm();
            for (Map.Entry<String, Object> entry : f.entrySet()) {
                assert entry.getValue().equals(form.getFirst(entry.getKey()));
            }

            MultiValueMap<String, FileUpload> files = request.getFiles();
            for (Map.Entry<String, IOStream> entry : fi.entrySet()) {
                FileUpload fileUpload = files.getFirst(entry.getKey());
                try {
                    Arrays.equals(fileUpload.get(), entry.getValue().toArray());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            response.htmlBody(hbody);
        }).requestMapping("/json", (request, response) -> {
            String json = request.jsonBody();
            assert j.equals(json);

            response.jsonBody(jbody);

            wait.set();
        });

        RxConfig.INSTANCE.setLogStrategy(LogStrategy.ALWAYS);
        HttpClient client = new HttpClient().withFeatures(false, true);
        assert hbody.equals(client.post(HttpClient.buildUrl("https://127.0.0.1:8081/api", qs), f, fi).toString());

        String resJson = client.postJson("https://127.0.0.1:8081/json", j).toString();
        JSONObject jobj = client.postJson("https://127.0.0.1:8081/json", j).toJson();
        System.out.println(jobj);

        System.out.println(resJson);
        assert jbody.equals(resJson);

        wait.waitOne();
        System.in.read();
    }

    @Test
    public void httpClient() {
        String headerString = "Host: api.m.jd.com\n" +
                "Connection: keep-alive\n" +
                "accept: application/json, text/plain, */*\n" +
                "x-rp-client: h5_1.0.0\n" +
                "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.117 Safari/537.36\n" +
                "x-referer-page: https://union.jd.com/proManager/index\n" +
                "Sec-Fetch-Site: same-site\n" +
                "Sec-Fetch-Mode: cors\n" +
                "Referer: https://union.jd.com/proManager/index\n" +
                "Accept-Encoding: gzip, deflate, br\n" +
                "Accept-Language: zh-CN,zh;q=0.9\n" +
                "Cookie: 3AB9D23F7A4B3C9B=ARGJW6C3BFDRDMLE2IVRJCQQ4NC65HDMB36IGPXUW7JJIMZ6MAYRK76GNV7RW6G2OXJJWE65MKVKERXQVNCPGEDE7E; shshshfpa=32cf9547-71b5-cb5d-1141-a6948a84ee1f-1711338862; shshshfpx=32cf9547-71b5-cb5d-1141-a6948a84ee1f-1711338862; pinId=aNUo6h3G0C3g20hktm5QsLV9-x-f3wj7; ceshi3.com=000; TrackID=1uEPlyXHGsCrw3e9T2CiwnS_Kg44jPCmW7M7JaJxheSPPiTdzr5ptE8cLdtgE3HjE16KVVNse8ZjaSYN1UaPDQTjYF7rSIE8suOSlO7WUY2c; thor=4E59707BBCC79D185665341B50BCF449ACFFCCD324A6233513038620008A4578A9B63966C063E636DD2B552DFA68D20B99AEE8AFAC979EED6D58FA8598B4AB9745C6DC0883922D019E86083B802AE95C42B2B3DF5A215C1B21708FB4A03DB22929C898E4CA1D6D9CECF6C5FEE278AB84C715963A11341C8F0013E50C0146B77986CEC20075563108A2B3A25C3E8C1B99AACC6E9A1AABB2D0604BC6D5298C5231; light_key=AASBKE7rOxgWQziEhC_QY6yaBurdXBQX7T4ZdhepQVZ01DnEa3XFaBp1llHlhQpl7X0dAnxZ; pin=jd_7251433dc47cc; unick=%E4%B9%90%E4%B9%8B%E5%A6%88%E5%92%AA24; __jdu=1718436564176204601267; __jdc=209449046; mba_muid=1718436564176204601267; __jd_ref_cls=MLoginRegister_SMSVerificationAppear; __jdv=209449046|direct|-|none|-|1732589133783; __jda=209449046.1718436564176204601267.1718436564.1732773234.1732846225.15; flash=3_aR-kwuagL7sh-LyDB0lZDT0uTa3Zw6B0RjNlMTQpp3jDKGhHYkYld59RiG3pP6y4jQzxtmjjjxQTENVpDWIivxf7cDKw-OGS4SkIoXRc_yiPTQ1Rs5dD8tXVC27d2h-g9T2wf8gjVoXBw_St29oxkaZhmyQhjEGgtL3Im2Zr8F5MKn8P8tet1e**; 3AB9D23F7A4B3CSS=jdd03ARGJW6C3BFDRDMLE2IVRJCQQ4NC65HDMB36IGPXUW7JJIMZ6MAYRK76GNV7RW6G2OXJJWE65MKVKERXQVNCPGEDE7EAAAAMTSWNDEMYAAAAACXBSN7WNY3LOIAX; _gia_d=1; shshshfpb=BApXS23qTlvZAOTtXg_Il6aOIz-MxQl_FBlNyj01X9xJ1MszOHYC2; __jdb=209449046.8298.1718436564176204601267|15.1732846225\n" +
                "\n\n\n";
        Map<String, String> headers = HttpClient.decodeHeader(Arrays.toList(Strings.split(headerString, "\n")));
        headers.remove("Host");
        System.out.println(JSON.toJSONString(headers, JSONWriter.Feature.PrettyFormat));

        HttpClient c = new HttpClient();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            c.requestHeaders().set(entry.getKey(), entry.getValue());
        }
        System.out.println(c.get("http://cn.bing.com").toString());
    }

    @Test
    public void restfulRpc() {
        String url = "http://x.cn/blog/1.html?userId=rx&type=1&userId=ft";
        Map<String, Object> map = (Map) HttpClient.decodeQueryString(url);
        assert map.get("userId").equals("ft");
        assert map.get("type").equals("1");
        map.put("userId", "newId");
        map.put("ok", "1");
        System.out.println(HttpClient.buildUrl(url, map));

        HttpUserManager facade = RestClient.facade(HttpUserManager.class, "https://ifconfig.co/", null);
        System.out.println(facade.queryIp());
    }

    @Test
    public void sftp() {
        SftpClient client = new SftpClient(AuthenticEndpoint.valueOf("root:Rxfli824@192.168.31.5:22"));
        String dir = "/home/rxlib/";
        if (client.exists(dir)) {
            client.delete(dir);
        }
        assert !client.exists(dir);
        client.createDirectory(dir);
        assert client.exists(dir);
        assert client.listDirectories("/home/", false).any(p -> p.getName().equals("rxlib"));
        InputStream f = Reflects.getResource("hosts.txt");
        client.saveFile(Files.concatPath(dir, "1.txt"), f);
        assert client.listFiles(dir, false).any(p -> p.getName().equals("1.txt"));
        HybridStream stream = new HybridStream();
        client.downloadFile(Files.concatPath(dir, "1.txt"), stream);
        System.out.println(IOStream.readString(stream.rewind().getReader(), StandardCharsets.UTF_8));

//        for (SftpFile dir : client.listDirectories("/home", true)) {
//            System.out.println(dir.getPath());
//        }
//        for (SftpFile file : client.listFiles("/home/rxsocks", true)) {
//            System.out.println(file.getPath());
//        }
    }

    @Test
    public void ping() {
        PingClient client = new PingClient();
        assert client.isReachable("192.168.31.1");
        PingClient.Result result = client.ping("www.baidu.com:80");
        System.out.println(toJsonString(result));
    }

    @Test
    public void crypt() {
        String content = str_content;
        byte[] key = str_name_wyf.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = AESUtil.generateKey(key).getEncoded();
        assert Arrays.equals(encoded, AESUtil.generateKey(key).getEncoded());

        ByteBuf src = Bytes.directBuffer();
        try {
            src.writeCharSequence(content, StandardCharsets.UTF_8);
            ByteBuf target = AESUtil.encrypt(src, key);

            ByteBuf recover = AESUtil.decrypt(target, key);
            String txt = (String) recover.readCharSequence(recover.readableBytes(), StandardCharsets.UTF_8);
            System.out.println(txt);
            assert content.equals(txt);
        } finally {
            src.release();
        }

        String encrypt = AESUtil.encryptToBase64(content);
//        String decrypt = AESUtil.decryptFromBase64(encrypt, String.format("℞%s", DateTime.utcNow().addDays(-1).toDateString()));
        String decrypt = AESUtil.decryptFromBase64(encrypt);
        System.out.println(decrypt);
        assert content.equals(decrypt);
    }

    @Test
    public void netUtil() {
        //authenticEndpoint
        String aep = "yf:123456@x.cn:1080?w=9";
        AuthenticEndpoint endpoint = AuthenticEndpoint.valueOf(aep);
        assert Sockets.toString(endpoint.getEndpoint()).equals("x.cn:1080");
        assert endpoint.getUsername().equals("yf");
        assert endpoint.getPassword().equals("123456");
        assert endpoint.toString().equals(aep);
        assert endpoint.getParameters().get("w").equals("9");

        aep = "yf:123456@x:1080";
        endpoint = AuthenticEndpoint.valueOf(aep);
        assert Sockets.toString(endpoint.getEndpoint()).equals("x.cn:1080");
        assert endpoint.getUsername().equals("yf");
        assert endpoint.getPassword().equals("123456");
        assert endpoint.toString().equals(aep);


        //bytes
        int a = 1;
        long b = Integer.MAX_VALUE + 1L;
        byte[] bytes = Bytes.getBytes(a);
        System.out.println(Arrays.toString(bytes));
        assert Bytes.getInt(bytes, 0) == a;
        bytes = Bytes.getBytes(b);
        System.out.println(Arrays.toString(bytes));
        assert Bytes.getLong(bytes, 0) == b;
    }

    @Test
    public void findProcess() {
        for (SocketInfo sock : Sockets.socketInfos(SocketProtocol.TCP)) {
            System.out.println(sock);
        }
    }
}
