package org.rx;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.Tuple;
import org.rx.core.App;
import org.rx.core.Reflects;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.TransportFlags;
import org.rx.net.socks.upstream.Socks5Upstream;

import java.util.Map;

import static org.rx.core.App.eq;

@Slf4j
public final class Main {
    //java -jar rxlib-2.17.3-SNAPSHOT.jar -shadowMode=1 -port=9900 -connectTimeout=120000 -shadowUser=youfanX:5PXx$^JNMgvn3P658@f-li.cn:9900
    //java -jar rxlib-2.17.3-SNAPSHOT.jar -port=9900 -connectTimeout=120000 -shadowServer=youfanX:5PXx$^JNMgvn3P658@103.79.76.126:9900
    public static void main(String[] args) {
        Map<String, String> options = App.argsOptions(args);
        Tuple<Boolean, Integer> port = Reflects.tryConvert(options.get("port"), Integer.class);
        if (port.right == null) {
            log.info("Invalid port arg");
            return;
        }

        Tuple<Boolean, Integer> connectTimeout = Reflects.tryConvert(options.get("connectTimeout"), Integer.class, 60000);
        String mode = options.get("shadowMode");
        if (eq(mode, "1")) {
            Tuple<Boolean, AuthenticEndpoint> authenticEndpoint = Reflects.tryConvert(options.get("shadowUser"), AuthenticEndpoint.class);
            if (authenticEndpoint.right == null) {
                System.out.println("Invalid shadowUser arg");
                return;
            }
            AuthenticEndpoint auth = authenticEndpoint.right;

            SocksConfig backConf = new SocksConfig(port.right, TransportFlags.FRONTEND_ALL.flags());
            backConf.setConnectTimeoutMillis(connectTimeout.right);
            SocksProxyServer backSvr = new SocksProxyServer(backConf, (u, p) -> eq(u, auth.getUsername()) && eq(p, auth.getPassword()), null);
        } else {
            Tuple<Boolean, AuthenticEndpoint> authenticEndpoint = Reflects.tryConvert(options.get("shadowServer"), AuthenticEndpoint.class);
            if (authenticEndpoint.right == null) {
                log.info("Invalid shadowServer arg");
                return;
            }

            SocksConfig frontConf = new SocksConfig(port.right, TransportFlags.BACKEND_ALL.flags());
            frontConf.setConnectTimeoutMillis(connectTimeout.right);
            SocksProxyServer frontSvr = new SocksProxyServer(frontConf, null, addr -> new Socks5Upstream(frontConf, authenticEndpoint.right));
        }

        log.info("Server started..");
        new Main().await();
    }

    @SneakyThrows
    synchronized void await() {
        wait();
    }
}
