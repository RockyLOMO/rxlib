package org.rx.util.rss;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.net.dns.DnsClient;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.support.UnresolvedEndpoint;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

@RequiredArgsConstructor
public final class RssRpcApp implements SocksRpcContract {
    final SocksProxyServer svrSide;

    @Override
    public void fakeEndpoint(BigInteger hash, String endpoint) {
        SocksRpcContract.fakeDict().putIfAbsent(hash, UnresolvedEndpoint.valueOf(endpoint));
    }

    @SneakyThrows
    @Override
    public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
        return DnsClient.outlandClient().resolveAll(host);
    }

    @Override
    public void addWhiteList(InetAddress endpoint) {
        svrSide.getConfig().allowWhiteList(endpoint);
    }

    @Override
    public boolean resetUdpRelay(int relayPort) {
        return svrSide.resetUdpRelay(relayPort);
    }

    @Override
    public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr) {
        return svrSide.claimUdpRelay(relayPort, clientAddr);
    }

    @SneakyThrows
    synchronized void await() {
        wait();
    }
}
