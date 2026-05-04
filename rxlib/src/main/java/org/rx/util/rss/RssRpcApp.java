package org.rx.util.rss;

import lombok.SneakyThrows;
import org.rx.net.dns.DnsClient;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcCapabilities;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.UdpRelayGroupOpenRequest;
import org.rx.net.socks.UdpRelayGroupOpenResult;
import org.rx.net.socks.UdpRelayGroupUpdateResult;
import org.rx.net.socks.Udp2rawOpenRequest;
import org.rx.net.socks.Udp2rawOpenResult;
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
    public void fakeEndpoint(long hash, String endpoint, String token) {
        SocksRpcContract.requireValidRpcToken(token);
        SocksRpcContract.fakeDict().putIfAbsent(hash, UnresolvedEndpoint.valueOf(endpoint));
    }

    @SneakyThrows
    @Override
    public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
        return DnsClient.remoteClient().resolveAll(host);
    }

    @Override
    public void addWhiteList(InetAddress endpoint, String token) {
        SocksRpcContract.requireValidRpcToken(token);
        svrSide.get().getConfig().allowWhiteList(endpoint);
    }

    @Override
    public boolean resetUdpRelay(int relayPort, String token) {
        return svrSide.get().resetUdpRelay(relayPort, token);
    }

    @Override
    public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr, String token) {
        return svrSide.get().claimUdpRelay(relayPort, clientAddr, token);
    }

    @Override
    public SocksRpcCapabilities capabilities(String token) {
        return svrSide.get().socksRpcCapabilities(token);
    }

    @Override
    public UdpRelayGroupOpenResult openUdpRelayGroup(UdpRelayGroupOpenRequest request, String token) {
        return svrSide.get().openUdpRelayGroup(request, token);
    }

    @Override
    public UdpRelayGroupUpdateResult addUdpRelays(String groupId, int count, String token) {
        return svrSide.get().addUdpRelays(groupId, count, token);
    }

    @Override
    public boolean removeUdpRelay(String groupId, int relayPort, String token) {
        return svrSide.get().removeUdpRelay(groupId, relayPort, token);
    }

    @Override
    public boolean heartbeatUdpRelayGroup(String groupId, String token) {
        return svrSide.get().heartbeatUdpRelayGroup(groupId, token);
    }

    @Override
    public boolean closeUdpRelayGroup(String groupId, String token) {
        return svrSide.get().closeUdpRelayGroup(groupId, token);
    }

    @Override
    public Udp2rawOpenResult openUdp2rawTunnel(Udp2rawOpenRequest request, String token) {
        return svrSide.get().openUdp2rawTunnel(request, token);
    }

    @Override
    public boolean heartbeatUdp2rawTunnel(String tunnelId, String token) {
        return svrSide.get().heartbeatUdp2rawTunnel(tunnelId, token);
    }

    @Override
    public boolean closeUdp2rawTunnel(String tunnelId, String token) {
        return svrSide.get().closeUdp2rawTunnel(tunnelId, token);
    }

    @SneakyThrows
    synchronized void await() {
        wait();
    }
}
