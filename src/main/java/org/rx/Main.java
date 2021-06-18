package org.rx;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.SUID;
import org.rx.bean.Tuple;
import org.rx.core.App;
import org.rx.core.Reflects;
import org.rx.core.Tasks;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.MemoryMode;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.http.HttpClient;
import org.rx.net.rpc.Remoting;
import org.rx.net.rpc.RpcClientConfig;
import org.rx.net.shadowsocks.ShadowsocksConfig;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.shadowsocks.encryption.CipherKind;
import org.rx.net.socks.SocksConfig;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.TransportFlags;
import org.rx.net.support.SocksSupport;
import org.rx.net.socks.upstream.Socks5Upstream;
import org.rx.security.AESUtil;
import org.rx.util.function.Action;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import static org.rx.core.App.eq;

@Slf4j
@RequiredArgsConstructor
public final class Main implements SocksSupport {
    @SneakyThrows
    public static void main(String[] args) {
        Map<String, String> options = App.argsOptions(args);
        Tuple<Boolean, Integer> port = Reflects.tryConvert(options.get("port"), Integer.class);
        if (port.right == null) {
            log.info("Invalid port arg");
            return;
        }

        Main app;
        Tuple<Boolean, Integer> connectTimeout = Reflects.tryConvert(options.get("connectTimeout"), Integer.class, 60000);
        String mode = options.get("shadowMode");
        if (eq(mode, "1")) {
            Tuple<Boolean, AuthenticEndpoint> shadowUser = Reflects.tryConvert(options.get("shadowUser"), AuthenticEndpoint.class);
            if (shadowUser.right == null) {
                System.out.println("Invalid shadowUser arg");
                return;
            }
            AuthenticEndpoint auth = shadowUser.right;

            SocksConfig backConf = new SocksConfig(port.right, TransportFlags.FRONTEND_COMPRESS.flags());
            backConf.setMemoryMode(MemoryMode.MEDIUM);
            backConf.setConnectTimeoutMillis(connectTimeout.right);
            SocksProxyServer backSvr = new SocksProxyServer(backConf, (u, p) -> eq(u, auth.getUsername()) && eq(p, auth.getPassword()), null);

            Remoting.listen(app = new Main(backSvr), port.right + 1);
        } else {
            Tuple<Boolean, AuthenticEndpoint> shadowServer = Reflects.tryConvert(options.get("shadowServer"), AuthenticEndpoint.class);
            if (shadowServer.right == null) {
                log.info("Invalid shadowServer arg");
                return;
            }

            SocksConfig frontConf = new SocksConfig(port.right, TransportFlags.BACKEND_COMPRESS.flags());
            frontConf.setMemoryMode(MemoryMode.MEDIUM);
            frontConf.setConnectTimeoutMillis(connectTimeout.right);
            SocksProxyServer frontSvr = new SocksProxyServer(frontConf, null, dstEp -> new Socks5Upstream(dstEp, frontConf, shadowServer.right));

            app = new Main(frontSvr);
            SocksSupport support = Remoting.create(SocksSupport.class, RpcClientConfig.poolMode(Sockets.newEndpoint(shadowServer.right.getEndpoint(), shadowServer.right.getEndpoint().getPort() + 1), 4));
            frontSvr.setSupport(support);

            Tuple<Boolean, Integer> shadowDnsPort = Reflects.tryConvert(options.get("shadowDnsPort"), Integer.class, 53);
            DnsServer frontDnsSvr = new DnsServer(shadowDnsPort.right);
            frontDnsSvr.setSupport(support);

            Action fn = () -> {
                InetAddress addr = InetAddress.getByName(HttpClient.getWanIp());
                if (!frontConf.getWhiteList().add(addr)) {
                    return;
                }
                support.addWhiteList(addr);
            };
            fn.invoke();
            Tasks.schedule(fn, 3000);

            ShadowsocksConfig ssConfig = new ShadowsocksConfig();
            ssConfig.setMemoryMode(MemoryMode.MEDIUM);
            ssConfig.setConnectTimeoutMillis(connectTimeout.right);
            ssConfig.setServerEndpoint(Sockets.getAnyEndpoint(port.right + 1));
            ssConfig.setMethod(CipherKind.AES_128_GCM.getCipherName());
            ssConfig.setPassword(shadowServer.right.getPassword());
            ShadowsocksServer server = new ShadowsocksServer(ssConfig, dstEp -> new Socks5Upstream(dstEp, frontConf, new AuthenticEndpoint(String.format("127.0.0.1:%s", port.right))));
        }

        log.info("Server started..");
        app.await();
    }

    final SocksProxyServer proxyServer;
    final DnsClient outlandClient = DnsClient.outlandClient();

    @Override
    public void fakeHost(SUID hash, String realHost) {
        realHost = AESUtil.decryptFromBase64(realHost);
        SocksSupport.HOST_DICT.put(hash, realHost);
    }

    @Override
    public List<InetAddress> resolveHost(String host) {
        host = AESUtil.decryptFromBase64(host);
        return outlandClient.resolveAll(host);
    }

    @Override
    public void addWhiteList(InetAddress endpoint) {
        proxyServer.getConfig().getWhiteList().add(endpoint);
    }

    @SneakyThrows
    synchronized void await() {
        wait();
    }
}
