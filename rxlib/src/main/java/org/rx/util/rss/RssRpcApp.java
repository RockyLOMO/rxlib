package org.rx.util.rss;

import lombok.SneakyThrows;
import org.rx.core.CachePolicy;
import org.rx.core.Strings;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsClient;
import org.rx.net.rpc.RemotingContext;
import org.rx.net.rpc.RemotingEventArgs;
import org.rx.net.socks.FakeEndpointRecovery;
import org.rx.net.socks.SocksProxyServer;
import org.rx.net.socks.SocksRpcCapabilities;
import org.rx.net.socks.SocksRpcContract;
import org.rx.net.socks.UdpRelayGroupOpenRequest;
import org.rx.net.socks.UdpRelayGroupOpenResult;
import org.rx.net.socks.UdpRelayGroupUpdateResult;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class RssRpcApp implements SocksRpcContract {
    private static final Object[] FAKE_ENDPOINT_LOCKS = new Object[64];

    static {
        for (int i = 0; i < FAKE_ENDPOINT_LOCKS.length; i++) {
            FAKE_ENDPOINT_LOCKS[i] = new Object();
        }
    }

    private final AtomicReference<SocksProxyServer> svrSide;
    private final WowEndpointRegistry wowEndpoints;

    @Override
    public boolean observeWowEndpoint(org.rx.net.socks.WowEndpointObservation observation) {
        if (observation == null) return false;
        SocksRpcContract.requireValidRpcToken(observation.rpcToken);
        org.rx.net.transport.core.TransportSession session = RemotingContext.context().getClient();
        org.rx.net.rpc.protocol.MetadataMessage metadata = session.attr("HandshakeMeta");
        WowEndpointRegistry registry = wowEndpoints;
        return metadata != null && metadata.getEventVersion() == SocksRpcContract.RPC_EVENT_VERSION
                && registry != null && registry.observe(observation.clientId, observation.writerKey, session, observation.readyIp, observation.ready);
    }

    @Override
    public String getObservedPublicIp(org.rx.net.socks.WowEndpointObservation observation) {
        if (observation == null) return null;
        SocksRpcContract.requireValidRpcToken(observation.rpcToken);
        org.rx.net.transport.core.TransportSession session = RemotingContext.context().getClient();
        org.rx.net.rpc.protocol.MetadataMessage metadata = session.attr("HandshakeMeta");
        WowEndpointRegistry registry = wowEndpoints;
        return metadata != null && metadata.getEventVersion() == SocksRpcContract.RPC_EVENT_VERSION && registry != null
                ? registry.observePeer(observation.clientId, observation.writerKey, session) : null;
    }

    public RssRpcApp(SocksProxyServer svrSide) {
        this(svrSide, null);
    }

    public RssRpcApp(SocksProxyServer svrSide, WowEndpointRegistry wowEndpoints) {
        this.svrSide = new AtomicReference<>(Objects.requireNonNull(svrSide, "svrSide"));
        this.wowEndpoints = wowEndpoints;
    }

    public void setServer(SocksProxyServer svrSide) {
        this.svrSide.set(Objects.requireNonNull(svrSide, "svrSide"));
    }

    @Override
    public boolean fakeEndpoint(String fakeHost, String endpoint, String token) {
        SocksRpcContract.requireValidRpcToken(token);
        if (!SocksRpcContract.isFakeHost(fakeHost)) {
            throw new IllegalArgumentException("invalid fake host");
        }
        return registerFakeEndpoint(fakeHost, Sockets.parseEndpoint(endpoint));
    }

    public InetSocketAddress recoverFakeEndpoint(String fakeHost) {
        FakeEndpointRecovery recovery = new FakeEndpointRecovery(fakeHost);
        RemotingEventArgs<FakeEndpointRecovery> args = RemotingEventArgs.compute(recovery);
        try {
            publishEvent(SocksRpcContract.EVENT_FAKE_ENDPOINT_RECOVERY, args);
        } catch (Throwable e) {
            return null;
        }

        FakeEndpointRecovery value = args.getValue();
        if (value == null || Strings.isEmpty(value.getRealEndpoint())) {
            return null;
        }
        InetSocketAddress endpoint = Sockets.parseEndpoint(value.getRealEndpoint());
        return registerFakeEndpoint(fakeHost, endpoint) ? endpoint : null;
    }

    private static boolean registerFakeEndpoint(String fakeHost, InetSocketAddress endpoint) {
        Object lock = FAKE_ENDPOINT_LOCKS[fakeHost.hashCode() & (FAKE_ENDPOINT_LOCKS.length - 1)];
        synchronized (lock) {
            InetSocketAddress existing = SocksRpcContract.fakeDict().get(fakeHost);
            if (existing != null && !existing.equals(endpoint)) {
                return false;
            }
            SocksRpcContract.fakeDict().put(fakeHost, endpoint,
                    CachePolicy.absolute(SocksRpcContract.FAKE_EXPIRE_SECONDS));
            return true;
        }
    }

    @SneakyThrows
    @Override
    public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
        return DnsClient.remoteClient().resolveAll(host);
    }

    @Override
    @SneakyThrows
    public byte[] queryDnsRecord(String domain, int type, boolean tcp, String token) {
        SocksRpcContract.requireValidRpcToken(token);
        return org.rx.net.dns.DnsRecordExchange.query(DnsClient.remoteNameServers(), domain, type, tcp);
    }

    @Override
    public void addWhiteList(InetAddress endpoint, String token) {
        SocksRpcContract.requireValidRpcToken(token);
        InetSocketAddress rpcPeer = RemotingContext.context().getClient().remoteEndpoint();
        InetAddress peerAddress = rpcPeer == null ? null : rpcPeer.getAddress();
        svrSide.get().getConfig().allowWhiteList(endpoint);
        if (peerAddress != null && !peerAddress.equals(endpoint)) {
            svrSide.get().getConfig().allowWhiteList(peerAddress);
        }

    }

    @Override
    public boolean resetUdpRelay(int relayPort, String token) {
        return svrSide.get().resetUdpRelay(relayPort, token);
    }

    @Override
    public boolean claimUdpRelay(int relayPort, InetSocketAddress clientAddr, String token) {
        RemotingContext ctx = RemotingContext.context();
        org.rx.net.transport.core.TransportSession session = ctx == null ? null : ctx.getClient();
        InetSocketAddress rpcPeer = session == null ? null : session.remoteEndpoint();
        InetAddress peerIp = rpcPeer == null ? null : rpcPeer.getAddress();
        // Behind NAT, client's local bind address/port differs from server-observed NAT peer.
        // Only lock exact clientAddr when claimed IP matches the authenticated RPC peer IP;
        // otherwise clear stale relay state and let SocksUdpRelayHandler whitelist + bind on first packet.
        if (clientAddr != null && peerIp != null && !peerIp.equals(clientAddr.getAddress())) {
            clientAddr = null;
        }
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

    @SneakyThrows
    synchronized void await() {
        wait();
    }
}
