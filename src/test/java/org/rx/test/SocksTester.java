package org.rx.test;

import io.netty.buffer.ByteBuf;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.Main;
import org.rx.bean.SUID;
import org.rx.core.App;
import org.rx.core.EventArgs;
import org.rx.core.Tasks;
import org.rx.io.Bytes;
import org.rx.io.IOStream;
import org.rx.net.*;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpClient;
import org.rx.net.http.RestClient;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RemotingException;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.rpc.RpcServerConfig;
import org.rx.net.shadowsocks.ShadowsocksClient;
import org.rx.net.shadowsocks.ShadowsocksConfig;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.socks.*;
import org.rx.net.support.SocksSupport;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.security.AESUtil;
import org.rx.test.bean.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.rx.core.App.sleep;
import static org.rx.core.App.toJsonString;

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
        RpcServerConfig serverConfig = new RpcServerConfig();
        serverConfig.setListenPort(3307);
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
        HttpUserManager facade = Remoting.create(HttpUserManager.class, RpcClientConfig.poolMode(endpoint0, threadCount));
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

    int connectTimeout = 30000;

    @SneakyThrows
    @Test
    public void ssProxy() {
        SocksConfig backConf = new SocksConfig(1082, TransportFlags.NONE.flags());
        SocksProxyServer backSvr = new SocksProxyServer(backConf);

        ShadowsocksConfig config = new ShadowsocksConfig();
        config.setServerEndpoint(Sockets.parseEndpoint("127.0.0.1:1081"));
        config.setMethod("aes-128-gcm");
        config.setPassword("123456");
//        ShadowsocksServer server = new ShadowsocksServer(config,
//                dstEp -> new Socks5Upstream(dstEp, backConf, new AuthenticEndpoint("127.0.0.1:1082")));
        ShadowsocksServer server = new ShadowsocksServer(config, null);

//        ShadowsocksClient client = new ShadowsocksClient(1080, config);

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void socks5Proxy() {
        int connectTimeoutMillis = 60000;
        SocksConfig backConf = new SocksConfig(1081, TransportFlags.FRONTEND_AES.flags());
        backConf.setConnectTimeoutMillis(connectTimeoutMillis);
        SocksProxyServer backSvr = new SocksProxyServer(backConf);
        Remoting.listen(new Main(backSvr), 1181);

        SocksConfig frontConf = new SocksConfig(1080, TransportFlags.BACKEND_AES.flags());
        frontConf.setConnectTimeoutMillis(connectTimeoutMillis);
        SocksProxyServer frontSvr = new SocksProxyServer(frontConf, null,
                dstEp -> new Socks5Upstream(dstEp, frontConf, new AuthenticEndpoint("127.0.0.1:1081")));
        SocksSupport support = Remoting.create(SocksSupport.class, RpcClientConfig.poolMode("127.0.0.1:1181", 2));
        frontSvr.setSupport(support);
        sleep(2000);
        support.addWhiteList(InetAddress.getByName(HttpClient.getWanIp()));

        System.in.read();
    }

    @SneakyThrows
    @Test
    public void directProxy() {
        SslDirectConfig frontConf = new SslDirectConfig(3307, TransportFlags.BACKEND_ALL.flags());
        SslDirectServer frontSvr = new SslDirectServer(frontConf, p -> Sockets.parseEndpoint("127.0.0.1:3308"));

        SslDirectConfig backConf = new SslDirectConfig(3308, TransportFlags.FRONTEND_ALL.flags());
        SslDirectServer backSvr = new SslDirectServer(backConf, p -> Sockets.parseEndpoint("rm-bp1hddend5q83p03g674.mysql.rds.aliyuncs.com:3306"));
        System.in.read();
    }

    @Test
    public void dns() {
        DnsServer server = new DnsServer(53);
        server.getCustomHosts().put("devops.f-li.cn", new byte[]{2, 2, 2, 2});

        //        System.out.println(HttpClient.godaddyDns("", "f-li.cn", "dd", "3.3.3.3"));
//        System.setProperty("sun.net.spi.nameservice.nameservers", "114.114.114.114");
//        System.setProperty("sun.net.spi.nameservice.provider.1", "dns,sun");

//        System.out.println(Sockets.resolveAddresses("devops.f-li.cn"));
//        System.out.println(Sockets.getAddresses("devops.f-li.cn"));

        sleep(2000);
        DnsClient client = new DnsClient(Sockets.parseEndpoint("192.168.31.7:853"));
        System.out.println(client.resolve("google.com"));
    }

    @Test
    public void crypt() {
        String content = "This is content";
        byte[] key = "顺风使舵".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = AESUtil.generateKey(key).getEncoded();
        assert org.rx.core.Arrays.equals(encoded, AESUtil.generateKey(key).getEncoded());

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

    @Test
    public void restClient() {
        HttpUserManager facade = RestClient.facade(HttpUserManager.class, "https://ifconfig.co/", null);
        System.out.println(facade.queryIp());
    }

    @Test
    public void httpClient() {
        HttpClient client = new HttpClient();
//        System.out.println(client.get("https://gitee.com/rx-code/rxlib").toString());
        System.out.println(IOStream.readString(client.get("https://f-li.cn").asStream().getReader(), StandardCharsets.UTF_8));
        client.get("https://f-li.cn").asFile("d:\\1.html");
    }

    @Test
    public void queryString() {
        String url = "http://f-li.cn/blog/1.html?userId=rx&type=1&userId=ft";
        Map<String, Object> map = (Map) HttpClient.parseQueryString(url);
        assert map.get("userId").equals("ft");
        assert map.get("type").equals("1");

        map.put("userId", "newId");
        map.put("ok", "1");
        System.out.println(HttpClient.buildQueryString(url, map));
        System.out.println(HttpClient.buildQueryString("http://f-li.cn/blog/1.html", map));
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

        SftpClient client = new SftpClient(new AuthenticEndpoint("rocky:@k8s.f-li.cn:22"));
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
        AuthenticEndpoint endpoint = new AuthenticEndpoint(aep);
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
