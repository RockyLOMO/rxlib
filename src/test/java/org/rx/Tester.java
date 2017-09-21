package org.rx;

import lombok.Data;
import org.rx.socket.Sockets;
import org.rx.util.BeanMapper;
import org.rx.util.RestClient;
import org.rx.util.StringBuilder;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.net.Socket;

public class Tester {
    public enum xCode {
        @ErrorCode(messageKeys = { "$arg" })
        arg,
        rv;
    }

    public static void main(String[] args) throws Exception {
        SystemException e = new SystemException(new Throwable()).setErrorCode(xCode.arg, "userId");
        System.out.println(e.getFriendlyMessage());

        //testSock();
        //        System.out.println(App.getBootstrapPath());

        //        System.out.println("--rx--");
        //        SystemException ex = new SystemException(new IllegalArgumentException());
        //        $<IllegalArgumentException> out = $();
        //        if (ex.tryGet(out, IllegalArgumentException.class)) {
        //            Exception e = out.$;
        //            System.out.println("ok" + e);
        //        }

        //        SourceBean sb = new SourceBean();
        //        App.changeType(sb, TargetBean.class);

        //        String sd = "2017";
        //        App.changeType(sd, Date.class);
    }

    private static void testSock() throws Exception {
        Socket sock = new Socket();
        sock.connect(Sockets.parseAddress("cn.bing.com:80"));
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
                System.out.println(
                        "-------------------------------------------0-----------------------------------------");
                break;
            }
            System.out.print(new String(chars, 0, read));
        }
        System.out.println("-------------------------------------------end-----------------------------------------");
    }

    private static void testRest() {
        String proxy = null;
        proxy = "127.0.0.1:8888";
        RestApi client = RestClient.create(RestApi.class, "http://localhost:8081", proxy, true);
        System.out.println(client.getClass());
        client.test();
        client.add(1, 1);
        client.login("Rocky", "abc123");
        ObjectParam p = new ObjectParam();
        p.setA(12);
        p.setB(12);
        client.add2(p);
    }

    private static void testMap() {
        BeanMapper mapper = new BeanMapper();
        mapper.setConfig(SourceBean.class, TargetBean.class, p -> {
            switch (p) {
                case "setInfo":
                    return "getName";
            }
            return null;
        }, "setLuckyNum");

        SourceBean f = new SourceBean();
        f.setName("HW ");
        f.setAge(100);
        f.setMoney(200L);
        TargetBean t = new TargetBean();
        t.setKids(10L);
        mapper.map(f, t, BeanMapper.Flags.TrimString | BeanMapper.Flags.SkipNull);
        System.out.println(t);
    }

    @Data
    public static class SourceBean {
        private String  name;
        private int     age;
        private boolean sex;
        private Long    money;
        private Long    kids;
    }

    @Data
    public static class TargetBean {
        private String     name;
        private String     age;
        private int        luckyNum;
        private BigDecimal money;
        private String     info;
        private Long       kids;
    }
}
