package org.rx.test;

import com.alibaba.fastjson.JSON;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.core.Arrays;
import org.rx.core.EventArgs;
import org.rx.core.Tasks;
import org.rx.socks.Sockets;
import org.rx.socks.tcp.RemotingFactor;
import org.rx.socks.tcp.TcpProxyServer;
import org.rx.socks.tcp.TcpServer;
import org.rx.test.bean.PersonInfo;
import org.rx.test.bean.UserEventArgs;
import org.rx.test.bean.UserManager;
import org.rx.test.bean.UserManagerImpl;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;

import static org.rx.core.Contract.sleep;

@Slf4j
public class SocksTester {
    private TcpServer<RemotingFactor.RemotingState> tcpServer;

//    @Test
//    public void apiRpc() {
//        UserManagerImpl server = new UserManagerImpl();
//        restartServer(server, 3307);
//
//        UserManager mgr1 = RemotingFactor.create(UserManager.class, Sockets.parseEndpoint("127.0.0.1:3307"), null,
//                p -> Sockets.parseEndpoint("127.0.0.1:3307"));
//        assert mgr1.computeInt(1, 1) == 2;
//
//        restartServer(server, 3307);
//
//        mgr1.testError();
//        assert mgr1.computeInt(2, 2) == 4;
//
//        UserManager mgr2 = RemotingFactor.create(UserManager.class, "127.0.0.1:3307");
//        assert mgr2.computeInt(1, 1) == 2;
//        mgr2.testError();
//        assert mgr2.computeInt(2, 2) == 4;
//
//        String event = "onAuth";
//        mgr1.<AuthEventArgs>attachEvent(event, (s, e) -> {
//            System.out.println(String.format("!!Mgr1 %s[flag=%s]!!", event, e.getFlag()));
//            e.setFlag(1);
//        });
//        mgr2.<AuthEventArgs>attachEvent(event, (s, e) -> {
//            System.out.println(String.format("!!Mgr2 %s[flag=%s]!!", event, e.getFlag()));
//            e.setFlag(2);
//        });
//
//        AuthEventArgs args = new AuthEventArgs(0);
//        mgr1.raiseEvent(event, args);
//        assert args.getFlag() == 1;
//
//        mgr2.raiseEvent(event, args);
//        assert args.getFlag() == 2;
//
//        sleep(1000);
//        args.setFlag(4);
//        server.raiseEvent(event, args);
//        assert args.getFlag() == 2;
//
//        mgr2.close();
//        sleep(2000);
//        args.setFlag(4);
//        server.raiseEvent(event, args);
//        assert args.getFlag() == 1;
//    }
//
//    private void restartServer(UserManagerImpl server, int port) {
//        if (tcpServer != null) {
//            tcpServer.close();
//        }
//        tcpServer = RemotingFactor.listen(server, port);
//        System.out.println("restartServer..");
//        sleep(4000);
//    }

    @SneakyThrows
    @Test
    public void implRpc() {
        //服务端监听
        UserManagerImpl server = new UserManagerImpl();
        RemotingFactor.listen(server, 3307);

        //客户端 facade
        UserManagerImpl facade = RemotingFactor.create(UserManagerImpl.class, "127.0.0.1:3307");
        assert facade.computeInt(1, 1) == 2; //服务端计算并返回
        facade.testError(); //测试异常
        assert facade.computeInt(17, 1) == 18;

        //注册事件（广播）
        facade.<UserEventArgs>attachEvent("onAddUser", (s, e) -> {
            log.info("event onAddUser with {} called", JSON.toJSONString(e));
            e.getResultList().addAll(Arrays.toList("a", "b", "c"));
            e.setCancel(false); //是否取消事件
        });
        sleep(1000);

        //服务端触发事件
        server.addUser(PersonInfo.def);

        //客户端触发事件
//        Tasks.run(() -> facade.addUser(PersonInfo.def));

        //自定义事件
        facade.attachEvent("onTest", (s, e) -> System.out.println("!!onTest!!"));
        sleep(1000);
        server.raiseEvent("onTest", EventArgs.Empty);
        sleep(1000);
        facade.raiseEvent("onTest", EventArgs.Empty);
        System.in.read();
    }

    @SneakyThrows
    @Test
    public void testProxy() {
        TcpProxyServer server = new TcpProxyServer(3307, null, p -> Sockets.parseEndpoint("rm-bp1utr02m6tp303p9.mysql.rds.aliyuncs.com:3306"));
        System.in.read();
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
