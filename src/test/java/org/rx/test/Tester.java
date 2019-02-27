package org.rx.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.rx.annotation.ErrorCode;
import org.rx.beans.$;
import org.rx.common.App;
import org.rx.common.SystemException;
import org.rx.socks.Sockets;
import org.rx.test.bean.*;
import org.rx.util.AsyncTask;

import java.io.*;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.rx.beans.$.$;
import static org.rx.common.Contract.eq;
import static org.rx.common.Contract.values;

public class Tester {
    @Test
    public void shorterUUID() {
        UUID id = UUID.randomUUID();
        String sid = App.toShorterUUID(id);
        UUID id2 = App.fromShorterUUID(sid);
        System.out.printf(sid);
        assert id.equals(id2);
    }

    @SneakyThrows
    @Test
    public void setTimeout() {
        AsyncTask.TaskFactory.scheduleOnce(() -> {
            System.out.println("hi!");
        }, 1000);
        System.in.read();
    }

    @Test
    public void testSock() throws Exception {
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

    @Test
    @ErrorCode(messageKeys = {"$x"})
    @ErrorCode(cause = IllegalArgumentException.class, messageKeys = {"$x"})
    public void testCode() {
        System.out.println(App.getBootstrapPath());

        String val = "rx";
        SystemException ex = new SystemException(values(val));
        assert eq(ex.getFriendlyMessage(), "Method Error Code value=\"" + val + "\"");

        ex = new SystemException(values(val), new IllegalArgumentException());
        assert eq(ex.getFriendlyMessage(), "This is IllegalArgumentException! \"" + val + "\"");
        $<IllegalArgumentException> out = $();
        assert ex.tryGet(out, IllegalArgumentException.class);

        String uid = "userId";
        ex.setErrorCode(UserCode.xCode.argument, uid);
        assert eq(ex.getFriendlyMessage(), "Enum Error Code value=\"" + uid + "\"");

        try {
            String date = "2017-08-24 02:02:02";
            App.changeType(date, Date.class);

            date = "x";
            App.changeType(date, Date.class);
        } catch (SystemException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testReadSetting() {
        Map<String, Object> map = App.loadYaml("application.yml");
        System.out.println(map);

        Object v = App.readSetting("not");
        assert v == null;

        v = App.readSetting("org.rx.test.Tester", null, SystemException.CodeFile);
        assert v instanceof Map;

        v = App.readSetting("org.rx.test.Tester.testCode<IllegalArgumentException>", null, SystemException.CodeFile);
        assert eq(v, "This is IllegalArgumentException! $x");
    }

    @Test
    @SneakyThrows
    public void testJson() {
        URL e = App.getClassLoader().getResource("jScript/");
        System.out.println(e);
        for (Path path : App.fileStream(Paths.get(e.toURI()))) {
            System.out.println(path);
            Map<String, Object> map = App.loadYaml(path.toString());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                System.out.println(entry.getKey() + ": " + entry.getValue());
            }
        }

        //        Object p = new TestServletRequest();
        //        System.out.println(Contract.toJsonString(p));
        //
        //        ErrorBean eb = new ErrorBean();
        //        System.out.println(Contract.toJsonString(eb));
        //
        //        System.out.println(Contract.toJsonString(eb));
    }
}
