package org.rx.test;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.core.Arrays;
import org.rx.core.EventArgs;
import org.rx.core.Tasks;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.PingClient;
import org.rx.net.Sockets;
import org.rx.net.http.HttpClient;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.net.tcp.*;
import org.rx.test.bean.*;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.rx.core.Contract.sleep;
import static org.rx.core.Contract.toJsonString;

@Slf4j
public class SocksTester {
    private TcpServer tcpServer;
    private TcpServer tcpServer2;

    @Test
    public void apiRpc() {
        UserManagerImpl server = new UserManagerImpl();
        restartServer(server, 3307);

        String ep = "127.0.0.1:3307";
        String groupA = "a", groupB = "b";
        List<UserManager> facadeGroupA = new ArrayList<>();
        facadeGroupA.add(RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupA, null));
        facadeGroupA.add(RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupA, null));

        for (UserManager facade : facadeGroupA) {
            assert facade.computeInt(1, 1) == 2;
        }
        //重启server，客户端自动重连
        restartServer(server, 3307);
        for (UserManager facade : facadeGroupA) {
            try {
                facade.testError();
            } catch (RemotingException e) {
            }
            assert facade.computeInt(2, 2) == 4;  //服务端计算并返回
        }

        List<UserManager> facadeGroupB = new ArrayList<>();
        facadeGroupB.add(RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupB, null));
        facadeGroupB.add(RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupB, null));

        for (UserManager facade : facadeGroupB) {
            assert facade.computeInt(1, 1) == 2;
            try {
                facade.testError();
            } catch (RemotingException e) {

            }
            assert facade.computeInt(2, 2) == 4;
        }

        //自定义事件（广播）
        String groupAEvent = "onAuth-A", groupBEvent = "onAuth-B";
        for (int i = 0; i < facadeGroupA.size(); i++) {
            int x = i;
            facadeGroupA.get(i).<UserEventArgs>attachEvent(groupAEvent, (s, e) -> {
                System.out.println(String.format("!!groupA - facade%s - %s[flag=%s]!!", x, groupAEvent, e.getFlag()));
                e.setFlag(e.getFlag() + 1);
            });
        }
        for (int i = 0; i < facadeGroupB.size(); i++) {
            int x = i;
            facadeGroupB.get(i).<UserEventArgs>attachEvent(groupBEvent, (s, e) -> {
                System.out.println(String.format("!!groupB - facade%s - %s[flag=%s]!!", x, groupBEvent, e.getFlag()));
                e.setFlag(e.getFlag() + 1);
            });
        }

        UserEventArgs args = new UserEventArgs(PersonBean.def);
        facadeGroupA.get(0).raiseEvent(groupAEvent, args);  //客户端触发事件，不广播
        assert args.getFlag() == 1;
        facadeGroupA.get(1).raiseEvent(groupAEvent, args);
        assert args.getFlag() == 2;

        server.raiseEvent(groupAEvent, args);
        assert args.getFlag() == 3;  //服务端触发事件，先执行最后一次注册事件，拿到最后一次注册客户端的EventArgs值，再广播其它组内客户端。

        facadeGroupB.get(0).raiseEvent(groupBEvent, args);
        assert args.getFlag() == 4;

        sleep(1000);
        args.setFlag(8);
        server.raiseEvent(groupBEvent, args);
        assert args.getFlag() == 9;

        facadeGroupB.get(0).close();  //facade接口继承AutoCloseable调用后可主动关闭连接
        sleep(1000);
        args.setFlag(16);
        server.raiseEvent(groupBEvent, args);
        assert args.getFlag() == 17;
    }

    @SneakyThrows
    private void epGroupReconnect() {
        UserManagerImpl server = new UserManagerImpl();
        restartServer(server, 3307);
        String ep = "127.0.0.1:3307";
        String groupA = "a", groupB = "b";

        UserManager userManager = RemotingFactory.create(UserManager.class, Sockets.parseEndpoint(ep), groupA, null, (p) -> {
            InetSocketAddress r;
            if (p.equals(Sockets.parseEndpoint(ep))) {
                r = Sockets.parseEndpoint("127.0.0.1:3308");
            } else {
                r = Sockets.parseEndpoint(ep);  //3307和3308端口轮询重试连接，模拟分布式不同端口重试连接
            }
            log.debug("reconnect {}", r);
            return r;
        }, true);
        assert userManager.computeInt(1, 1) == 2;
        sleep(1000);
        tcpServer.close();  //关闭3307
        Tasks.scheduleOnce(() -> tcpServer2 = RemotingFactory.listen(server, 3308), 32000);  //32秒后开启3308端口实例，重连3308成功
        System.in.read();
    }

    private void restartServer(UserManagerImpl server, int port) {
        if (tcpServer != null) {
            tcpServer.close();
        }
        tcpServer = RemotingFactory.listen(server, port);
        System.out.println("restartServer on port " + port);
        sleep(2600);
    }

    @Test
    public void implRpc() {
        //服务端监听
        UserManagerImpl server = new UserManagerImpl();
        RemotingFactory.listen(server, 3307);

        //客户端 facade
        UserManagerImpl facade = RemotingFactory.create(UserManagerImpl.class, Sockets.parseEndpoint("127.0.0.1:3307"), "", (s, e) -> {
            System.out.println("onHandshake: " + s.computeInt(1, 2));
        });
        assert facade.computeInt(1, 1) == 2; //服务端计算并返回
        try {
            facade.testError(); //测试异常
        } catch (RemotingException e) {

        }
        assert facade.computeInt(17, 1) == 18;

        //注册事件（广播）
        facade.<UserEventArgs>attachEvent("onAddUser", (s, e) -> {
            log.info("Event onAddUser with {} called", toJsonString(e));
            e.getResultList().addAll(Arrays.toList("a", "b", "c"));
            e.setCancel(false); //是否取消事件
        });
        sleep(1000);

        //服务端触发事件
        server.addUser(PersonBean.def);
        //客户端触发事件
        facade.addUser(PersonBean.def);

        //自定义事件
        String eventName = "onCustom";
        facade.attachEvent(eventName, (s, e) -> System.out.println(String.format("CustomEvent %s called", eventName)));
        sleep(1000);
        server.raiseEvent(eventName, EventArgs.EMPTY);
        sleep(1000);
        facade.raiseEvent(eventName, EventArgs.EMPTY);
    }

    @Test
    public void poolRpc() {
        RemotingFactory.listen(HttpUserManager.INSTANCE, 3307);

        HttpUserManager facade = RemotingFactory.create(HttpUserManager.class, "127.0.0.1:3307");
        for (int i = 0; i < 50; i++) {
            facade.computeInt(1, i);
        }
    }

//    @SneakyThrows
//    @Test
//    public void proxyServer() {
//        TcpProxyServer server = new TcpProxyServer(3307, null, p -> Sockets.parseEndpoint("rm-bp1utr02m6tp303p9.mysql.rds.aliyuncs.com:3306"));
//        System.in.read();
//    }

    @SneakyThrows
    @Test
    public void socks5() {
        SocksConfig config = new SocksConfig();
        config.setListenPort(1081);
        config.setUpstreamSupplier(addr -> new Socks5Upstream("127.0.0.1:1080"));
        SocksProxyServer ss = new SocksProxyServer(config);
        ss.setAuthenticator((username, password) -> username.equals("ss"));
        ss.start();

        System.in.read();
    }

    @Test
    public void ping() {
        PingClient.test("x.f-li.cn:80", r -> log.info(toJsonString(r)));
    }

    @Test
    public void queryString() {
        String url = "http://f-li.cn/blog/1.html?userId=rx&type=1&userId=ft";
        Map<String, Object> map = (Map) HttpClient.parseQueryString(url);
        System.out.println(toJsonString(map));

        map.put("userId", "newId");
        map.put("ok", "1");
        System.out.println(HttpClient.buildQueryString(url, map));
        System.out.println(HttpClient.buildQueryString("http://f-li.cn/blog/1.html", map));
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
    public void testSock() throws Exception {
        Socket sock = new Socket();
        sock.connect(Sockets.parseEndpoint("cn.bing.com:80"));
        OutputStreamWriter writer = new OutputStreamWriter(sock.getOutputStream());
        InputStreamReader reader = new InputStreamReader(sock.getInputStream());

        writer.write("GET http://cn.bing.com/ HTTP/1.1\n" + "Host: cn.bing.com\n" + "Connection: keep-alive\n"
                + "Upgrade-Insecure-Requests: 1\n"
                + "User-Agent: Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36\n"
                + "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8\n"
                + "Accept-Language: en-US,en;q=0.8,zh-CN;q=0.6,zh;q=0.4\n"
                + "Cookie: SRCHD=AF=NOFORM; SRCHUID=V=2&GUID=1FF56C28058C4383AA2F55B195E647D6; SRCHUSR=DOB=20170626; _EDGE_V=1; MUIDB=015F2A0A091D66D73D8220A708BC67BD; _FP=hta=on; ipv6=hit=1; MUID=015F2A0A091D66D73D8220A708BC67BD; SRCHUID=V=2&GUID=534B102E514B4BBEB0CE2D3BCCB4AFB9&dmnchg=1; _FS=intlF=0; ULC=T=13A21|3:2; ENSEARCH=BENVER=0; KievRPSAuth=FABqARRaTOJILtFsMkpLVWSG6AN6C/svRwNmAAAEgAAACIXraXMlB3gGKAHzJpuUbMT/zOsb/a25vc1H0IoTZpt1O1uySVDZO%2BovQNHCdhWzuXWmvyb1LI2hPVJ/kklS9WVu/xeCt%2BOW/xeVlZlaV9VRjhupl9ehH0EpWJX439klugieOCAuw%2B3OH6Lt%2BkXJ2%2BgPKg%2BnbkePrQeMtUv6aLPS0Cpd2YGagO6/HdXZ4A9IWOHaIUHbtK%2BJcv4yUOuSXGWLraydjOSg3pFp/Yxn1Z5Tnv3VYY/DlXJaf4lRJk2uvisNilK3cfLeLEiUMXgkF0fP4kgttGKjw53AVhnZuooav3/G8pA4WGft9wayhi3tZAFwGyHMx9apXsN2exUIRpNBuZGU2OiE6fDw8JZsh7qq4s3BIPTGt8w7o%2BFhQQlaXyFPm6Hn8EyhNluEGpmcBQN1FRQAT4UfQk%2BY%2BA%2B9AppAFmpROw7jWYI%3D; PPLState=1; ANON=A=33BD40D822128DAAF64483D8FFFFFFFF&E=1408&W=1; NAP=V=1.9&E=13ae&C=fuLYQzW-OJFOR2H2cRdDFb2QoDQNta_7g8cAzMtejqfOb_rHAbWLUQ&W=1; WLS=TS=63637415244&C=&N=; SRCHHPGUSR=CW=1366&CH=638&DPR=1&UTC=480&WTS=63640090933; _EDGE_S=mkt=zh-cn&F=1&SID=1DF36AE19B156DF715DE604C9AB46C09; _SS=SID=1DF36AE19B156DF715DE604C9AB46C09&HV=1505213776&bIm=493449; WLID=m+wbGIcFt6HnZDpKwtUUq/TM2YcyxG9eMs72NbkfbXpRwRHDhwKOZ+Be/UtSTFLUyxjp5MslDHzRa4dFgqjQlUCHCzTpDD5qBjh1HtrTkFg=\n"
                + "\n");
        writer.flush();

        char[] chars = new char[512];
        int read;
        while ((read = reader.read(chars, 0, chars.length)) >= 0) {
            if (read == 0) {
                System.out.println("-------------------------------------------0-----------------------------------------");
                break;
            }
            System.out.print(new String(chars, 0, read));
        }
        System.out.println("-------------------------------------------end-----------------------------------------");
    }
}
