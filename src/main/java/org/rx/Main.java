package org.rx;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.core.App;
import org.rx.core.Reflects;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.TransportFlags;
import org.rx.net.socks.support.SocksSupport;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.security.AESUtil;

import java.util.Map;

import static org.rx.core.App.eq;

@Slf4j
public final class Main implements SocksSupport {
    public static void main(String[] args) {
        Map<String, String> options = App.argsOptions(args);
        Tuple<Boolean, Integer> port = Reflects.tryConvert(options.get("port"), Integer.class);
        if (port.right == null) {
            log.info("Invalid port arg");
            return;
        }

        Main app = new Main();
        Tuple<Boolean, Integer> connectTimeout = Reflects.tryConvert(options.get("connectTimeout"), Integer.class, 60000);
        String mode = options.get("shadowMode");
        if (eq(mode, "1")) {
            Tuple<Boolean, AuthenticEndpoint> shadowUser = Reflects.tryConvert(options.get("shadowUser"), AuthenticEndpoint.class);
            if (shadowUser.right == null) {
                System.out.println("Invalid shadowUser arg");
                return;
            }
            AuthenticEndpoint auth = shadowUser.right;

            Remoting.listen(app, port.right + 1);

            SocksConfig backConf = new SocksConfig(port.right, TransportFlags.FRONTEND_COMPRESS.flags());
            backConf.setMemoryMode(MemoryMode.MEDIUM);
            backConf.setConnectTimeoutMillis(connectTimeout.right);
            SocksProxyServer backSvr = new SocksProxyServer(backConf, (u, p) -> eq(u, auth.getUsername()) && eq(p, auth.getPassword()), null);
        } else {
            Tuple<Boolean, AuthenticEndpoint> shadowServer = Reflects.tryConvert(options.get("shadowServer"), AuthenticEndpoint.class);
            if (shadowServer.right == null) {
                log.info("Invalid shadowServer arg");
                return;
            }

            SocksSupport support = Remoting.create(SocksSupport.class, RpcClientConfig.poolMode(Sockets.newEndpoint(shadowServer.right.getEndpoint(), shadowServer.right.getEndpoint().getPort() + 1), 2));

            SocksConfig frontConf = new SocksConfig(port.right, TransportFlags.BACKEND_COMPRESS.flags());
            frontConf.setMemoryMode(MemoryMode.MEDIUM);
            frontConf.setConnectTimeoutMillis(connectTimeout.right);
            SocksProxyServer frontSvr = new SocksProxyServer(frontConf, null, dstEp -> new Socks5Upstream(dstEp, frontConf, shadowServer.right));
            frontSvr.setSupport(support);
        }

        log.info("Server started..");
        app.await();
    }

    @Override
    public void fakeHost(SUID hash, String realHost) {
        realHost = AESUtil.decryptFromBase64(realHost);
        SocksSupport.HOST_DICT.put(hash, realHost);
    }

    @SneakyThrows
    synchronized void await() {
        wait();
    }
}
