package org.rx.test;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.multipart.FileUpload;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.junit.jupiter.api.Test;
import org.rx.Main;
import org.rx.bean.MultiValueMap;
import org.rx.bean.RandomList;
import org.rx.bean.SUID;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;
import org.rx.io.IOStream;
import org.rx.net.*;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.*;
import org.rx.net.nameserver.NameserverClient;
import org.rx.net.nameserver.NameserverConfig;
import org.rx.net.nameserver.NameserverImpl;
import org.rx.net.rpc.*;
import org.rx.net.shadowsocks.ShadowsocksConfig;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.shadowsocks.encryption.CipherKind;
import org.rx.net.socks.*;
import org.rx.net.socks.upstream.Socks5UdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.*;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.security.AESUtil;
import org.rx.test.bean.*;
import org.rx.util.function.TripleAction;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.App.*;

@Slf4j
public class SocksTester extends TConfig {
    final Map<Object, RpcServer> serverHost = new ConcurrentHashMap<>();
    final long startDelay = 4000;
    final String eventName = "onCallback";

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
    String appUsercenter = "usercenter";
    String appOrder = "order";
    String node1 = String.format("127.0.0.1:%s", conf1.getRegisterPort());
    String node2 = String.format("127.0.0.1:%s", conf2.getRegisterPort());

    @Test
    public void singleNode() {
        NameserverClient c1 = new NameserverClient(appUsercenter);
        NameserverClient c2 = new NameserverClient(appOrder);

        c1.registerAsync(node1).join();
        List<InetAddress> discover = c1.discover(appUsercenter);
        System.out.println(toJsonString(discover));
        assert discover.contains(Sockets.loopbackAddress());

        discover = c1.discover(appOrder);
        assert CollectionUtils.isEmpty(discover);

        c2.registerAsync(node1).join();

        discover = c1.discover(appOrder);
        assert discover.contains(Sockets.loopbackAddress());

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

    private void sleep4Sync() {
        sleep(5000);
        System.out.println("-等待异步同步-");
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
        c1.wait4Inject();

        sleep4Sync();
        System.out.println("x:" + ns1.getDnsServer().getHosts());
        System.out.println("x2:" + ns2.getDnsServer().getHosts());
    }

    @SneakyThrows
    @Test
    public void rpcStatefulApi() {
        UserManagerImpl svcImpl = new UserManagerImpl();
        startServer(svcImpl, endpoint_3307);

        List<UserManager> facadeGroup = new ArrayList<>();
        facadeGroup.add(Remoting.create(UserManager.class, RpcClientConfig.statefulMode(endpoint_3307, 0)));
        facadeGroup.add(Remoting.create(UserManager.class, RpcClientConfig.statefulMode(endpoint_3307, 0)));

        tst(svcImpl, facadeGroup);
        //重启server，客户端自动重连
        restartServer(svcImpl, endpoint_3307, startDelay);
        tst(svcImpl, facadeGroup);

//        facadeGroup.get(0).close();  //facade接口继承AutoCloseable调用后可主动关闭连接
    }

    private void tst(UserManagerImpl svcImpl, List<UserManager> facadeGroup) {
        for (UserManager facade : facadeGroup) {
            try {
                facade.triggerError();
            } catch (RemotingException e) {
            }
            assert facade.computeInt(1, 1) == 2;  //服务端计算并返回
        }

        //自定义事件（广播） 只加1次
        for (int i = 0; i < facadeGroup.size(); i++) {
            int x = i;
            facadeGroup.get(i).<UserEventArgs>attachEvent(eventName, (s, e) -> {
                System.out.printf("!!facade%s - args.flag=%s!!%n", x, e.getFlag());
                e.setFlag(e.getFlag() + 1);
            }, false);
        }
        for (int i = 0; i < 10; i++) {
            UserEventArgs args = new UserEventArgs(PersonBean.girl);
            facadeGroup.get(0).raiseEvent(eventName, args);
            System.out.println("flag:" + args.getFlag());
            assert args.getFlag() == 1;

            args = new UserEventArgs(PersonBean.girl);
            args.setFlag(1);
            facadeGroup.get(1).raiseEvent(eventName, args);
            assert args.getFlag() == 2;

            svcImpl.raiseEvent(eventName, args);
            sleep(50);
            assert args.getFlag() == 3;  //服务端触发事件，先执行最后一次注册事件，拿到最后一次注册客户端的EventArgs值，再广播其它组内客户端。
        }
    }

    @Test
    public void rpcStatefulImpl() {
        //服务端监听
        RpcServerConfig serverConfig = new RpcServerConfig(3307);
        serverConfig.setEventComputeVersion(2);  //版本号一样的才会去client计算eventArgs再广播
//        serverConfig.getEventBroadcastVersions().add(2);  //版本号一致才广播
        UserManagerImpl server = new UserManagerImpl();
        Remoting.listen(server, serverConfig);

        //客户端 facade
        RpcClientConfig config = RpcClientConfig.statefulMode("127.0.0.1:3307", 1);
        UserManagerImpl facade1 = Remoting.create(UserManagerImpl.class, config, (p, c) -> {
            System.out.println("onHandshake: " + p.computeInt(1, 2));
        });
        assert facade1.computeInt(1, 1) == 2; //服务端计算并返回
        try {
            facade1.triggerError(); //测试异常
        } catch (RemotingException e) {
        }
        assert facade1.computeInt(17, 1) == 18;

        config.setEventVersion(2);
        UserManagerImpl facade2 = Remoting.create(UserManagerImpl.class, config, null);
        //注册事件（广播）
        attachEvent(facade1, "0x00");
        //服务端触发事件，只有facade1注册会被广播到
        server.create(PersonBean.girl);

        attachEvent(facade2, "0x01");
        //服务端触发事件，facade1,facade2随机触发计算eventArgs，然后用计算出的eventArgs广播非计算的facade
        server.create(PersonBean.girl);

        //客户端触发事件
        facade1.create(PersonBean.girl);
        sleep(5000);
    }

    private void attachEvent(UserManagerImpl facade, String id) {
        facade.<UserEventArgs>attachEvent("onCreate", (s, e) -> {
            log.info("facade{} onCreate -> {}", id, toJsonString(e));
            e.getStatefulList().add(id + ":" + SUID.randomSUID());
            e.setCancel(false); //是否取消事件
        });
    }

    @Test
    public void rpcReconnect() {
        UserManagerImpl svcImpl = new UserManagerImpl();
        startServer(svcImpl, endpoint_3307);
        AtomicBoolean connected = new AtomicBoolean(false);
        UserManager userManager = Remoting.create(UserManager.class, RpcClientConfig.statefulMode(endpoint_3307, 0), (p, c) -> {
            p.attachEvent(eventName, (s, e) -> {
                System.out.println("attachEvent callback");
            }, false);
            System.out.println("attachEvent done");

            c.onReconnecting.combine((s, e) -> {
                InetSocketAddress next;
                if (e.getValue().equals(endpoint_3307)) {
                    next = endpoint_3308;
                } else {
                    next = endpoint_3307;  //3307和3308端口轮询重试连接，模拟分布式不同端口重试连接
                }
                log.debug("reconnect {}", next);
                e.setValue(next);
            });
            log.debug("init ok");
            connected.set(true);
        });
        assert userManager.computeInt(1, 1) == 2;
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
            assert userManager.computeInt(i, 1) == i + 1;
            i++;
        }
        userManager.raiseEvent(eventName, EventArgs.EMPTY);
        userManager.raiseEvent(eventName, EventArgs.EMPTY);
    }

    <T> void startServer(T svcImpl, InetSocketAddress ep) {
        RpcServerConfig svr = new RpcServerConfig(ep.getPort());
        svr.setUseRuntimeTcpEventLoop(false);
        serverHost.computeIfAbsent(svcImpl, k -> Remoting.listen(k, svr));
        System.out.println("Start server on port " + ep.getPort());
    }

    <T> Future<?> restartServer(T svcImpl, InetSocketAddress ep, long startDelay) {
        RpcServer rs = serverHost.remove(svcImpl);
        sleep(startDelay);
        rs.close();
        System.out.println("Close server on port " + rs.getConfig().getListenPort());
        return Tasks.setTimeout(() -> startServer(svcImpl, ep), startDelay);
    }

    @SneakyThrows
    @Test
    public void rpcPoolMode() {
        Remoting.listen(HttpUserManager.INSTANCE, endpoint_3307.getPort());

        int tcount = 200;
        CountDownLatch latch = new CountDownLatch(tcount);
        //没有事件订阅，无状态会使用连接池模式
        int threadCount = 8;
        HttpUserManager facade = Remoting.create(HttpUserManager.class, RpcClientConfig.poolMode(endpoint_3307, 1, threadCount));
        for (int i = 0; i < tcount; i++) {
            int finalI = i;
            Tasks.run(() -> {
                facade.computeInt(1, finalI);
                App.sleep(1000);
                latch.countDown();
            });
        }
        latch.await();
    }

    @SneakyThrows
    @Test
    public synchronized void udpRpc() {
        UdpClient c1 = new UdpClient(endpoint_3307.getPort());
        c1.onReceive.combine((s, e) -> System.out.println("c1: " + toJsonString(e)));
        UdpClient c2 = new UdpClient(endpoint_3308.getPort());
        AtomicInteger count = new AtomicInteger();
        c2.onReceive.combine((s, e) -> {
            System.out.println("c2:" + toJsonString(e));
            if (count.incrementAndGet() < 2) {
                throw new InvalidException("error");
            }
        });

        c1.sendAsync(endpoint_3307, "我是1");
        for (int i = 0; i < 10; i++) {
            int finalI = i;
            Tasks.run(() -> c2.sendAsync(endpoint_3307, "我是2 + " + finalI));
        }

        c1.sendAsync(endpoint_3308, "wlz", 15000, true);
        System.out.println("done");
        wait();
    }

    int connectTimeoutMillis = 30000;

    @SneakyThrows
    @Test
    public void ssProxy() {
        int shadowDnsPort = 853;
        DnsServer dnsSvr = new DnsServer(shadowDnsPort);
        InetSocketAddress shadowDnsEp = Sockets.localEndpoint(shadowDnsPort);
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));

        String defPwd = "123456";
        SocksConfig backConf = new SocksConfig(2080);
        backConf.setEnableUdp2raw(true);
        backConf.setUdp2rawServers(Collections.emptyList());
        SocksUser usr = new SocksUser("rocky");
        usr.setPassword(defPwd);
        usr.setMaxIpCount(-1);
        SocksProxyServer backSvr = new SocksProxyServer(backConf, Authenticator.dbAuth(Collections.singletonList(usr), null));

        AuthenticEndpoint srvEp = new AuthenticEndpoint(Sockets.localEndpoint(backConf.getListenPort()), usr.getUsername(), usr.getPassword());
        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.anyEndpoint(2090),
                CipherKind.AES_128_GCM.getCipherName(), defPwd);
        ShadowsocksServer server = new ShadowsocksServer(config, dstEp -> {
            //must first
            if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                return shadowDnsUpstream;
            }
            //bypass
            if (config.isBypass(dstEp.getHost())) {
                return new Upstream(dstEp);
            }
            return new Socks5Upstream(dstEp, backConf, () -> new UpstreamSupport(srvEp, null));
        }, dstEp -> {
            //must first
            if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                return shadowDnsUpstream;
            }
            //bypass
            if (config.isBypass(dstEp.getHost())) {
                return new Upstream(dstEp);
            }
            return new Upstream(dstEp);
//            return new Upstream(dstEp, srvEp);
        });

//        ShadowsocksClient client = new ShadowsocksClient(1080, config);

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void socks5Proxy() {
        boolean udp2raw = false;
        boolean udp2rawDirect = false;
        Udp2rawHandler.DEFAULT.setGzipMinLength(40);

        InetSocketAddress backSrvEp = Sockets.localEndpoint(2080);
        int shadowDnsPort = 853;
        //backend
        SocksConfig backConf = new SocksConfig(backSrvEp.getPort());
        backConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
        backConf.setConnectTimeoutMillis(connectTimeoutMillis);
        backConf.setEnableUdp2raw(udp2raw);
        SocksProxyServer backSvr = new SocksProxyServer(backConf, null);
//        backSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

        RpcServerConfig rpcServerConf = new RpcServerConfig(backSrvEp.getPort() + 1);
        rpcServerConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
        Remoting.listen(new Main(backSvr), rpcServerConf);

        //dns
        DnsServer dnsSvr = new DnsServer(shadowDnsPort);
        InetSocketAddress shadowDnsEp = Sockets.localEndpoint(shadowDnsPort);
//        Sockets.injectNameService(shadowDnsEp);

        //frontend
        RandomList<UpstreamSupport> shadowServers = new RandomList<>();
        RpcClientConfig rpcClientConf = RpcClientConfig.poolMode(Sockets.newEndpoint(backSrvEp, backSrvEp.getPort() + 1), 2, 2);
        rpcClientConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
        shadowServers.add(new UpstreamSupport(new AuthenticEndpoint(backSrvEp), Remoting.create(SocksSupport.class, rpcClientConf)));

        SocksConfig frontConf = new SocksConfig(2090);
        frontConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
        frontConf.setConnectTimeoutMillis(connectTimeoutMillis);
        frontConf.setEnableUdp2raw(udp2raw);
        if (!udp2rawDirect) {
            frontConf.setUdp2rawServers(Arrays.toList(backSrvEp));
        } else {
            frontConf.setUdp2rawServers(Collections.emptyList());
        }
        SocksProxyServer frontSvr = new SocksProxyServer(frontConf);
        Upstream shadowDnsUpstream = new Upstream(new UnresolvedEndpoint(shadowDnsEp));
        TripleAction<SocksProxyServer, RouteEventArgs> firstRoute = (s, e) -> {
            UnresolvedEndpoint dstEp = e.getDestinationEndpoint();
            //must first
            if (dstEp.getPort() == SocksSupport.DNS_PORT) {
                e.setValue(shadowDnsUpstream);
                return;
            }
            //bypass
            if (frontConf.isBypass(dstEp.getHost())) {
                e.setValue(new Upstream(dstEp));
            }
        };
        frontSvr.onRoute.combine(firstRoute, (s, e) -> {
            if (e.getValue() != null) {
                return;
            }
            e.setValue(new Socks5Upstream(e.getDestinationEndpoint(), frontConf, () -> shadowServers.next()));
        });
        frontSvr.onUdpRoute.combine(firstRoute, (s, e) -> {
            if (e.getValue() != null) {
                return;
            }
            UnresolvedEndpoint dstEp = e.getDestinationEndpoint();
            if (frontConf.isEnableUdp2raw()) {
                if (!udp2rawDirect) {
                    e.setValue(new Upstream(dstEp, shadowServers.next().getEndpoint()));
                } else {
                    e.setValue(new Upstream(dstEp));
                }
                return;
            }
            e.setValue(new Socks5UdpUpstream(dstEp, frontConf, shadowServers::next));
        });
//        frontSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

//        sleep(2000);
//        for (UpstreamSupport support : shadowServers) {
//            support.getSupport().addWhiteList(InetAddress.getByName(HttpClient.getWanIp()));
//        }

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void directProxy() {
        DirectConfig frontConf = new DirectConfig(3307);
        frontConf.setTransportFlags(TransportFlags.BACKEND_AES_COMBO.flags());
        DirectProxyServer frontSvr = new DirectProxyServer(frontConf, p -> Sockets.parseEndpoint("127.0.0.1:3308"));

        DirectConfig backConf = new DirectConfig(3308);
        backConf.setTransportFlags(TransportFlags.FRONTEND_AES_COMBO.flags());
        DirectProxyServer backSvr = new DirectProxyServer(backConf, p -> Sockets.parseEndpoint("rm-bp1hddend5q83p03g674.mysql.rds.aliyuncs.com:3306"));

        System.in.read();
    }

    @Test
    public void isBypass() {
        SocketConfig conf = new SocketConfig();
        assert conf.isBypass("127.0.0.1");
        assert conf.isBypass("192.168.31.1");
        assert !conf.isBypass("192.169.31.1");
        assert conf.isBypass("localhost");
        assert !conf.isBypass("google.cn");

        IPAddress ipAddress = IPSearcher.DEFAULT.search("x.f-li.cn");
        System.out.println(ipAddress);
    }

    @SneakyThrows
    @Test
    public void dnsInject() {
        Sockets.injectNameService(Collections.singletonList(Sockets.parseEndpoint("192.168.137.2:853")));

        InetAddress localHost = InetAddress.getLocalHost();
        System.out.println(localHost);

        InetAddress loopbackAddress = InetAddress.getLoopbackAddress();
        System.out.println(loopbackAddress.equals(Sockets.loopbackAddress()));

        InetAddress[] all = InetAddress.getAllByName(host_devops);
        System.out.println(java.util.Arrays.toString(all));
    }

    @SneakyThrows
    @Test
    public synchronized void dns() {
        //        System.out.println(HttpClient.godaddyDns("", "f-li.cn", "dd", "3.3.3.3"));
        InetSocketAddress nsEp = Sockets.parseEndpoint("114.114.114.114:53");
        InetSocketAddress localNsEp = Sockets.parseEndpoint("127.0.0.1:54");

        final InetAddress ip2 = InetAddress.getByName("2.2.2.2");
        final InetAddress ip4 = InetAddress.getByName("4.4.4.4");
        DnsServer server = new DnsServer(54, nsEp);
        server.setShadowServers(new RandomList<>(Collections.singletonList(new UpstreamSupport(null, new SocksSupport() {
            @Override
            public void fakeEndpoint(SUID hash, String realEndpoint) {

            }

            @Override
            public List<InetAddress> resolveHost(String host) {
                return DnsClient.inlandClient().resolveAll(host);
            }

            @Override
            public void addWhiteList(InetAddress endpoint) {

            }
        }))));
        server.setHostsTtl(5);
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
            _exit();
        }, 6000);

        InetAddress wanIp = InetAddress.getByName(HttpClient.getWanIp());
//        IPAddress current = IPSearcher.DEFAULT.current();
//        System.out.println(current);
        List<InetAddress> currentIps = DnsClient.inlandClient().resolveAll(host_devops);
        System.out.println("ddns: " + wanIp + " = " + currentIps);
        //注入变更 InetAddress.getAllByName 内部查询dnsServer的地址，支持非53端口
        Sockets.injectNameService(Collections.singletonList(localNsEp));

        List<InetAddress> wanResult = DnsClient.inlandClient().resolveAll(host_devops);
        InetAddress[] localResult = InetAddress.getAllByName(host_devops);
        System.out.println("wanResolve: " + wanResult + " != " + toJsonString(localResult));
        assert !wanResult.get(0).equals(localResult[0]);

        server.addHostsFile(path("hosts.txt"));
        assert client.resolve(host_cloud).equals(InetAddress.getByName("192.168.31.7"));

        wait();
    }

    @Test
    public void findProcess() {
        for (SocketInfo sock : Sockets.socketInfos(SocketProtocol.TCP)) {
            System.out.println(sock);
        }
    }

    @Test
    public void crypt() {
        String content = "This is content";
        byte[] key = "顺风使舵".getBytes(StandardCharsets.UTF_8);
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
    }

    @Test
    public void bytes() {
        int a = 1;
        long b = Integer.MAX_VALUE + 1L;
        byte[] bytes = Bytes.getBytes(a);
        System.out.println(Arrays.toString(bytes));
        assert Bytes.getInt(bytes, 0) == a;
        bytes = Bytes.getBytes(b);
        System.out.println(Arrays.toString(bytes));
        assert Bytes.getLong(bytes, 0) == b;
    }

    @SneakyThrows
    @Test
    public void httpServer() {
        ManualResetEvent wait = new ManualResetEvent();
        Map<String, Object> qs = new HashMap<>();
        qs.put("a", "1");
        qs.put("b", "乐之");

        Map<String, Object> f = new HashMap<>();
        f.put("a", "1");
        f.put("b", "乐之");

        Map<String, IOStream<?, ?>> fi = new HashMap<>();
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
            for (Map.Entry<String, IOStream<?, ?>> entry : fi.entrySet()) {
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

        HttpClient client = new HttpClient();
        assert hbody.equals(client.post(HttpClient.buildUrl("https://127.0.0.1:8081/api", qs), f, fi).asString());

        String resJson = client.postJson("https://127.0.0.1:8081/json", j).asString();
        System.out.println(jbody);
        System.out.println(resJson);
        assert jbody.equals(resJson);

        wait.waitOne();

        System.in.read();
    }

    @Test
    public void restClient() {
        HttpUserManager facade = RestClient.facade(HttpUserManager.class, "https://ifconfig.co/", null);
        System.out.println(facade.queryIp());
    }

    @Test
    public void queryString() {
        String url = "http://f-li.cn/blog/1.html?userId=rx&type=1&userId=ft";
        Map<String, Object> map = (Map) HttpClient.decodeQueryString(url);
        assert map.get("userId").equals("ft");
        assert map.get("type").equals("1");

        map.put("userId", "newId");
        map.put("ok", "1");
        System.out.println(HttpClient.buildUrl(url, map));
        System.out.println(HttpClient.buildUrl("http://f-li.cn/blog/1.html", map));
    }

    @Test
    public void sftp() {
//        SftpClient client = new SftpClient(new AuthenticEndpoint("jks:123456@mobile.f-li.cn:2222"));
//        String dir = DateTime.now().toString("yyyyMMdd");
//        for (SftpClient.FileEntry listDirectory : client.listDirectories("/", false)) {
//            if (listDirectory.getFilename().equals(dir)) {
//                continue;
//            }
//            client.delete(listDirectory.getPath());
//        }

        SftpClient client = new SftpClient(AuthenticEndpoint.valueOf("rocky:@k8s.f-li.cn:22"));
        for (SftpFile directory : client.listDirectories("/home/rocky/df/", true)) {
            System.out.println(directory.getPath());
        }
        for (SftpFile file : client.listFiles("/home/rocky/df/", true)) {
            System.out.println(file.getPath());
        }
        System.out.println(client.exists("/home/rocky/df/scpx.sh"));
        System.out.println(client.exists("/home/rocky/df/scpx2.sh"));
        System.out.println(client.exists("/home/rocky/df/"));
        System.out.println(client.exists("/home/rocky/df"));

//        String p = "E:\\Photo\\养生\\f0.jpg";
//        client.uploadFile(p,"/test/");
//        client.downloadFile("/test/f0.jpg","F:\\test\\1.jpg");
//        client.delete("/test/");
//        for (SftpClient.FileEntry lsEntry : client.listFiles("/", true)) {
//            System.out.println(toJsonString(lsEntry));
//        }
    }

    @Test
    public void authenticEndpoint() {
        String aep = "yf:123456@f-li.cn:1080?w=9";
        AuthenticEndpoint endpoint = AuthenticEndpoint.valueOf(aep);
        assert Sockets.toString(endpoint.getEndpoint()).equals("f-li.cn:1080");
        assert endpoint.getUsername().equals("yf");
        assert endpoint.getPassword().equals("123456");
        assert endpoint.toString().equals(aep);
        assert endpoint.getParameters().get("w").equals("9");

        aep = "yf:123456@f-li.cn:1080";
        endpoint = AuthenticEndpoint.valueOf(aep);
        assert Sockets.toString(endpoint.getEndpoint()).equals("f-li.cn:1080");
        assert endpoint.getUsername().equals("yf");
        assert endpoint.getPassword().equals("123456");
        assert endpoint.toString().equals(aep);
    }

    @Test
    public void ping() {
        PingClient.test("cloud.f-li.cn:50112", r -> log.info(toJsonString(r)));
    }
}
