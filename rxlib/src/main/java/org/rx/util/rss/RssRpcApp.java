package org.rx.util.rss;

import lombok.SneakyThrows;
import org.rx.net.dns.DnsClient;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class RssRpcApp implements SocksRpcContract {
    private final AtomicReference<SocksProxyServer> svrSide;

    public RssRpcApp(SocksProxyServer svrSide) {
        this.svrSide = new AtomicReference<>(Objects.requireNonNull(svrSide, "svrSide"));
    }

    public void setServer(SocksProxyServer svrSide) {
        this.svrSide.set(Objects.requireNonNull(svrSide, "svrSide"));
    }

    @Override
    public void fakeEndpoint(long hash, String endpoint) {
        SocksRpcContract.fakeDict().putIfAbsent(hash, UnresolvedEndpoint.valueOf(endpoint));
    }

    @SneakyThrows
    @Override
    public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
        return DnsClient.remoteClient().resolveAll(host);
    }

    @Override
    public void addWhiteList(InetAddress endpoint) {
        svrSide.get().getConfig().allowWhiteList(endpoint);
    }

    @Override
    public boolean resetUdpRelay(int relayPort) {
        return svrSide.get().resetUdpRelay(relayPort);
    }

    @Override
    public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr) {
        return svrSide.get().claimUdpRelay(relayPort, clientAddr);
    }

    @SneakyThrows
    synchronized void await() {
        wait();
    }
}
