package org.rx.test;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.multipart.FileUpload;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.Main;
import org.rx.bean.MultiValueMap;
import org.rx.bean.RandomList;
import org.rx.bean.SUID;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.io.Bytes;
import org.rx.io.IOStream;
import org.rx.net.*;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.*;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingException;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.shadowsocks.ShadowsocksConfig;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.shadowsocks.encryption.CipherKind;
import org.rx.net.socks.*;
import org.rx.net.socks.upstream.UdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.*;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.security.AESUtil;
import org.rx.test.bean.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.rx.core.App.*;

@Slf4j
public class SocksTester {
    final InetSocketAddress endpoint0 = Sockets.parseEndpoint("127.0.0.1:3307");
    final InetSocketAddress endpoint1 = Sockets.parseEndpoint("127.0.0.1:3308");
    final Map<Object, Remoting.ServerBean> serverHost = new ConcurrentHashMap<>();
    final long startDelay = 4000;
    final String eventName = "onCallback";

    @Test
    public void rpc_StatefulApi() {
        UserManagerImpl svcImpl = new UserManagerImpl();
        startServer(svcImpl, endpoint0);

        String ep = "127.0.0.1:3307";
        List<UserManager> facadeGroup = new ArrayList<>();
        facadeGroup.add(Remoting.create(UserManager.class, RpcClientConfig.statefulMode(ep, 0)));
        facadeGroup.add(Remoting.create(UserManager.class, RpcClientConfig.statefulMode(ep, 0)));

        for (UserManager facade : facadeGroup) {
            assert facade.computeInt(1, 1) == 2;
        }
        //重启server，客户端自动重连
        restartServer(svcImpl, endpoint0, startDelay);
        for (UserManager facade : facadeGroup) {
            try {
                facade.triggerError();
            } catch (RemotingException e) {
            }
            assert facade.computeInt(2, 2) == 4;  //服务端计算并返回
        }
        //自定义事件（广播）
        String groupEvent = "onAuth";
        for (int i = 0; i < facadeGroup.size(); i++) {
            int x = i;
            facadeGroup.get(i).<UserEventArgs>attachEvent(groupEvent, (s, e) -> {
                System.out.println(String.format("!!facade%s - args.flag=%s!!", x, e.getFlag()));
                e.setFlag(e.getFlag() + 1);
            });
        }

        for (int i = 0; i < 10; i++) {
            UserEventArgs args = new UserEventArgs(PersonBean.girl);
            facadeGroup.get(0).raiseEvent(groupEvent, args);
            assert args.getFlag() == 1;

            args = new UserEventArgs(PersonBean.girl);
            args.setFlag(1);
            facadeGroup.get(1).raiseEvent(groupEvent, args);
            assert args.getFlag() == 2;

            svcImpl.raiseEvent(groupEvent, args);
            assert args.getFlag() == 3;  //服务端触发事件，先执行最后一次注册事件，拿到最后一次注册客户端的EventArgs值，再广播其它组内客户端。
        }
//        facadeGroup.get(0).close();  //facade接口继承AutoCloseable调用后可主动关闭连接
    }

    @Test
    public void rpc_StatefulImpl() {
        //服务端监听
        RpcServerConfig serverConfig = new RpcServerConfig(3307);
        serverConfig.setEventComputeVersion(2);  //版本号一样的才会去client计算eventArgs再广播
//        serverConfig.getEventBroadcastVersions().add(2);  //版本号一致才广播
        UserManagerImpl server = new UserManagerImpl();
        Remoting.listen(server, serverConfig);

        //客户端 facade
        RpcClientConfig config = RpcClientConfig.statefulMode("127.0.0.1:3307", 1);
        UserManagerImpl facade1 = Remoting.create(UserManagerImpl.class, config, p -> {
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
        sleep(6000);
    }

    private void attachEvent(UserManagerImpl facade, String id) {
        facade.<UserEventArgs>attachEvent("onCreate", (s, e) -> {
            log.info("facade{} onCreate -> {}", id, toJsonString(e));
            e.getStatefulList().add(id + ":" + SUID.randomSUID());
            e.setCancel(false); //是否取消事件
        });
    }

    @Test
    public void rpc_Reconnect() {
        UserManagerImpl svcImpl = new UserManagerImpl();
        startServer(svcImpl, endpoint0);
        AtomicBoolean connected = new AtomicBoolean(false);
        UserManager userManager = Remoting.create(UserManager.class, RpcClientConfig.statefulMode(endpoint0, 0), p -> {
            p.attachEvent(eventName, (s, e) -> {
                System.out.println("attachEvent callback");
            }, false);
            System.out.println("attachEvent done");
        }, c -> {
            c.onReconnecting = (s, e) -> {
                InetSocketAddress next;
                if (e.getValue().equals(endpoint0)) {
                    next = endpoint1;
                } else {
                    next = endpoint0;  //3307和3308端口轮询重试连接，模拟分布式不同端口重试连接
                }
                log.debug("reconnect {}", next);
                e.setValue(next);
            };
            log.debug("init ok");
            connected.set(true);
        });
        assert userManager.computeInt(1, 1) == 2;
        userManager.raiseEvent(eventName, EventArgs.EMPTY);

//        userManager.close();

        restartServer(svcImpl, endpoint1, 8000); //10秒后开启3308端口实例，重连3308成功
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
        serverHost.computeIfAbsent(svcImpl, k -> Remoting.listen(k, ep.getPort()));
        System.out.println("Start server on port " + ep.getPort());
        sleep(startDelay);
    }

    <T> void restartServer(T svcImpl, InetSocketAddress ep, long startDelay) {
        Remoting.ServerBean bean = serverHost.remove(svcImpl);
        Objects.requireNonNull(bean);
        sleep(startDelay);
        bean.getServer().close();
        System.out.println("Close server on port " + bean.getServer().getConfig().getListenPort());
        Tasks.scheduleOnce(() -> startServer(svcImpl, ep), startDelay);
    }

    @SneakyThrows
    @Test
    public void rpc_clientPool() {
        Remoting.listen(HttpUserManager.INSTANCE, endpoint0.getPort());

        int tcount = 200;
        CountDownLatch latch = new CountDownLatch(tcount);
        //没有事件订阅，无状态会使用连接池模式
        int threadCount = 8;
        HttpUserManager facade = Remoting.create(HttpUserManager.class, RpcClientConfig.poolMode(endpoint0, 1, threadCount));
        for (int i = 0; i < tcount; i++) {
            int finalI = i;
            Tasks.run(() -> {
                facade.computeInt(1, finalI);
                App.sleep(1000);
                latch.countDown();
            });
        }
        latch.await();
//        System.in.read();
    }

    int connectTimeoutMillis = 30000;

    @SneakyThrows
    @Test
    public void ssProxy() {
        String defPwd = "123456";
        SocksConfig backConf = new SocksConfig(1082);
        SocksUser usr = new SocksUser("rocky");
        usr.setPassword(defPwd);
        usr.setMaxIpCount(-1);
        SocksProxyServer backSvr = new SocksProxyServer(backConf, Authenticator.dbAuth(Collections.singletonList(usr), null), null);

        DnsServer frontDnsSvr = new DnsServer(853);
        UnresolvedEndpoint loopbackDns = new UnresolvedEndpoint("127.0.0.1", 53);

        ShadowsocksConfig config = new ShadowsocksConfig(Sockets.parseEndpoint("127.0.0.1:1081"),
                CipherKind.AES_128_GCM.getCipherName(), defPwd);
        ShadowsocksServer server = new ShadowsocksServer(config, dstEp -> {
//            return new DirectUpstream(dstEp);
            if (dstEp.equals(loopbackDns)) {
                return new Upstream(new UnresolvedEndpoint(dstEp.getHost(), 853));
            }
            return new Socks5Upstream(dstEp, backConf, new AuthenticEndpoint(Sockets.localEndpoint(1082), usr.getUsername(), usr.getPassword()));
        });

//        ShadowsocksClient client = new ShadowsocksClient(1080, config);

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void socks5Proxy() {
        boolean udp2raw = true;
        InetSocketAddress backSrvEp = Sockets.localEndpoint(2080);
        //backend
        SocksConfig backConf = new SocksConfig(backSrvEp.getPort());
        backConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
        backConf.setConnectTimeoutMillis(connectTimeoutMillis);
        backConf.setEnableUdp2raw(udp2raw);
        SocksProxyServer backSvr = new SocksProxyServer(backConf, null, null,
                dstEp -> {
                    log.info("backend udp {}", dstEp);
                    return new UdpUpstream(dstEp);
                });
//        backSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

        RpcServerConfig rpcServerConf = new RpcServerConfig(backSrvEp.getPort() + 1);
        rpcServerConf.setTransportFlags(TransportFlags.FRONTEND_COMPRESS.flags());
        Remoting.listen(new Main(backSvr), rpcServerConf);

        //frontend
        RandomList<UpstreamSupport> supports = new RandomList<>();
        RpcClientConfig rpcClientConf = RpcClientConfig.poolMode(Sockets.newEndpoint(backSrvEp, backSrvEp.getPort() + 1), 2, 2);
        rpcClientConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
        supports.add(new UpstreamSupport(new AuthenticEndpoint(backSrvEp),
                Remoting.create(SocksSupport.class, rpcClientConf)));

        SocksConfig frontConf = new SocksConfig(2090);
        frontConf.setTransportFlags(TransportFlags.BACKEND_COMPRESS.flags());
        frontConf.setConnectTimeoutMillis(connectTimeoutMillis);
        frontConf.setEnableUdp2raw(udp2raw);
        frontConf.setUdp2rawServers(new RandomList<>(Arrays.toList(backSrvEp)));
        SocksProxyServer frontSvr = new SocksProxyServer(frontConf, null,
                dstEp -> new Socks5Upstream(dstEp, frontConf, supports),
                dstEp -> {
//                    log.info("frontend udp {}", dstEp);
//                    return new UdpProxyUpstream(dstEp, supports);
                    return new UdpUpstream(dstEp);
                });
//        frontSvr.setAesRouter(SocksProxyServer.DNS_AES_ROUTER);

        sleep(2000);
        for (UpstreamSupport support : supports) {
            support.getSupport().addWhiteList(InetAddress.getByName(HttpClient.getWanIp()));
        }

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
    public void dns() {
        //        System.out.println(HttpClient.godaddyDns("", "f-li.cn", "dd", "3.3.3.3"));
        InetSocketAddress nsEp = Sockets.parseEndpoint("114.114.114.114:53");
        InetSocketAddress localNsEp = Sockets.parseEndpoint("127.0.0.1:54");

        final String host = "devops.f-li.cn";
        final InetAddress hostIp = InetAddress.getByName("2.2.2.2");
        DnsServer server = new DnsServer(54, nsEp);
        server.setSupport(new RandomList<>(Collections.singletonList(new UpstreamSupport(null, new SocksSupport() {
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
        server.addHosts(host, hostIp);

        //注入变更 InetAddress.getAllByName 内部查询dnsServer的地址，支持非53端口
        Sockets.injectNameService(localNsEp);

        List<InetAddress> wanResult = DnsClient.inlandClient().resolveAll(host);
        InetAddress[] localResult = InetAddress.getAllByName(host);
        System.out.println(wanResult + "\n" + toJsonArray(localResult));
        assert !wanResult.get(0).equals(localResult[0]);

        DnsClient client = new DnsClient(localNsEp);
        InetAddress result = client.resolve(host);
        assert result.equals(hostIp);

        server.addHostsFile(TConfig.path("hosts.txt"));
        client.resolve("cloud.f-li.cn").equals(InetAddress.getByName("192.168.31.7"));

//        String cacheDomain = "www.baidu.com";
//        InetAddress resolve = client.resolve(cacheDomain);
//        System.out.println(resolve);
//
//        sleep((60 + 10) * 1000);
//        client.clearCache();
//        resolve = client.resolve(cacheDomain);
//        System.out.println(resolve);

//        System.in.read();
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
        String aep = "yf:123456@d.f-li.cn:1080";
        AuthenticEndpoint endpoint = AuthenticEndpoint.valueOf(aep);
        assert Sockets.toString(endpoint.getEndpoint()).equals("d.f-li.cn:1080");
        assert endpoint.getUsername().equals("yf");
        assert endpoint.getPassword().equals("123456");
        assert endpoint.toString().equals(aep);
    }

    @Test
    public void ping() {
        PingClient.test("cloud.f-li.cn:80", r -> log.info(toJsonString(r)));
    }
}
