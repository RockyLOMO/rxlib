package org.rx.net.socks;

import org.rx.net.udp.*;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;
import org.rx.core.Tasks;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
final class Udp2rawServerEntryManager extends Disposable {
    private static final int MAX_TUNNELS = 4096;

    final SocksProxyServer server;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentMap<String, Udp2rawTunnelContext> tunnels = new ConcurrentHashMap<>();
    private final List<Channel> entryChannels = new ArrayList<>();
    private final ScheduledFuture<?> cleanupTask;
    private volatile InetSocketAddress entryAddress;

    Udp2rawServerEntryManager(SocksProxyServer server) {
        this.server = server;
        this.cleanupTask = Tasks.schedulePeriod(this::cleanupIdleTunnels, 30_000L);
    }

    synchronized void start() {
        checkNotClosed();
        if (!entryChannels.isEmpty()) {
            return;
        }
        SocketAddress bindAddress = resolveBindAddress();
        if (!(bindAddress instanceof InetSocketAddress)) {
            throw new IllegalStateException("udp2raw fixed entry requires InetSocketAddress");
        }
        SocksConfig config = server.getConfig();
        Udp2rawServerEntryHandler handler = new Udp2rawServerEntryHandler(this);
        List<Channel> channels = Sockets.bindChannels(Sockets.udpBootstrap(config, ch -> {
            ChannelPipeline p = ch.pipeline();
            if (config.getUdpReadTimeoutSeconds() > 0 || config.getUdpWriteTimeoutSeconds() > 0) {
                p.addLast(new ProxyChannelIdleHandler(config.getUdpReadTimeoutSeconds(), config.getUdpWriteTimeoutSeconds()));
            }
            p.addLast(handler);
        }).attr(SocksContext.SOCKS_SVR, server), bindAddress, config);
        entryChannels.addAll(channels);
        if (!entryChannels.isEmpty()) {
            InetSocketAddress local = (InetSocketAddress) entryChannels.get(0).localAddress();
            entryAddress = Socks5CommandRequestHandler.resolveUdpRelayAdvertiseAddress(config.getInetListenAddress(), local);
        }
        log.info("udp2raw fixed entry started address={} channels={}", Sockets.toString(entryAddress), entryChannels.size());
    }

    Udp2rawOpenResult open(Udp2rawOpenRequest request) {
        checkNotClosed();
        if (entryChannels.isEmpty() || entryAddress == null) {
            return Udp2rawOpenResult.fail("ENTRY_NOT_STARTED", "udp2raw fixed entry is not started");
        }
        if (request == null) {
            return Udp2rawOpenResult.fail("BAD_REQUEST", "request is null");
        }
        int version = request.getProtocolVersion() <= 0 ? Udp2rawCodec.VERSION : request.getProtocolVersion();
        if (version != Udp2rawCodec.VERSION) {
            return Udp2rawOpenResult.fail("BAD_VERSION", "unsupported protocol version " + version);
        }
        if (tunnels.size() >= MAX_TUNNELS) {
            return Udp2rawOpenResult.fail("TOO_MANY_TUNNELS", "too many udp2raw tunnels");
        }

        SocksConfig config = server.getConfig();
        long now = System.currentTimeMillis();
        long sessionHi = random.nextLong();
        long sessionLo = random.nextLong();
        while (sessionHi == 0L && sessionLo == 0L) {
            sessionHi = random.nextLong();
            sessionLo = random.nextLong();
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        int maxSessions = request.getMaxSessions() > 0
                ? Math.min(request.getMaxSessions(), config.getUdp2rawMaxSessions())
                : config.getUdp2rawMaxSessions();
        long idleMillis = (request.getIdleTimeoutSeconds() > 0
                ? request.getIdleTimeoutSeconds()
                : config.getUdp2rawSessionIdleSeconds()) * 1000L;
        UdpCompressConfig compressConfig = negotiateCompress(config, request.getCompress());
        UdpRedundantConfig redundantConfig = negotiateRedundant(config, request.getRedundant());
        UdpRedundantMode redundantMode = negotiateRedundantMode(config, request.getRedundantMode());
        TrafficUser trafficUser = resolveTrafficUser(request);
        String tunnelId = tunnelId(sessionHi, sessionLo);
        Udp2rawTunnelContext context = new Udp2rawTunnelContext(this, tunnelId, sessionHi, sessionLo,
                secret, config.getUdp2rawAuthMode(), compressConfig, redundantConfig, redundantMode,
                idleMillis, maxSessions, config.getUdpMtu(), trafficUser, now);
        tunnels.put(tunnelId, context);
        DiagnosticMetrics.record("socks.udp2raw.tunnel.open.count", 1D, "result=success");
        DiagnosticMetrics.record("socks.udp2raw.tunnel.active.count", tunnels.size(), "action=open");
        return Udp2rawOpenResult.success(tunnelId, sessionHi, sessionLo, secret, entryAddress,
                context.expireAtMillis(), capabilities(compressConfig, redundantConfig));
    }

    boolean heartbeat(String tunnelId) {
        Udp2rawTunnelContext context = tunnels.get(tunnelId);
        if (context == null) {
            return false;
        }
        context.touch();
        return true;
    }

    boolean closeTunnel(String tunnelId) {
        Udp2rawTunnelContext context = tunnels.remove(tunnelId);
        if (context == null) {
            return false;
        }
        context.close("rpc-close");
        DiagnosticMetrics.record("socks.udp2raw.tunnel.active.count", tunnels.size(), "action=close");
        return true;
    }

    Udp2rawTunnelContext context(Udp2rawFrame frame) {
        if (frame == null) {
            return null;
        }
        return tunnels.get(tunnelId(frame.getSessionHi(), frame.getSessionLo()));
    }

    Udp2rawTunnelContext context(String tunnelId) {
        return tunnelId != null ? tunnels.get(tunnelId) : null;
    }

    Udp2rawCapabilities capabilities() {
        return capabilities(configCompress(), configRedundant());
    }

    private Udp2rawCapabilities capabilities(UdpCompressConfig compressConfig, UdpRedundantConfig redundantConfig) {
        SocksConfig config = server.getConfig();
        Udp2rawCapabilities capabilities = new Udp2rawCapabilities();
        capabilities.setMaxSessions(config.getUdp2rawMaxSessions());
        capabilities.setAuthMode(config.getUdp2rawAuthMode());
        capabilities.setReusePort(Sockets.reusePortBindCount(config, resolveBindAddress()) > 1);
        capabilities.setCompress(Udp2rawPayloadSupport.isCompressEnabled(compressConfig));
        capabilities.setRedundant(Udp2rawPayloadSupport.isRedundantEnabled(redundantConfig));
        return capabilities;
    }

    InetSocketAddress entryAddress() {
        return entryAddress;
    }

    private SocketAddress resolveBindAddress() {
        SocksConfig config = server.getConfig();
        if (config.getUdp2rawListenAddress() != null) {
            return config.getUdp2rawListenAddress();
        }
        SocketAddress listenAddress = config.getListenAddress();
        if (listenAddress instanceof InetSocketAddress && ((InetSocketAddress) listenAddress).getPort() > 0) {
            return listenAddress;
        }
        if (!server.tcpChannels.isEmpty()) {
            SocketAddress local = server.tcpChannels.get(0).localAddress();
            if (local instanceof InetSocketAddress) {
                return local;
            }
        }
        return Sockets.newAnyEndpoint(config.getListenPort());
    }

    private UdpCompressConfig negotiateCompress(SocksConfig config, UdpCompressConfig requested) {
        if (!config.isUdpCompressEnabled() || !Udp2rawPayloadSupport.isCompressEnabled(requested)) {
            return null;
        }
        UdpCompressConfig serverConfig = configCompress();
        if (!Udp2rawPayloadSupport.isCompressEnabled(serverConfig)
                || serverConfig.getCodec() != requested.getCodec()
                || serverConfig.getDictionaryId() != requested.getDictionaryId()) {
            return null;
        }
        return serverConfig;
    }

    private UdpRedundantConfig negotiateRedundant(SocksConfig config, UdpRedundantConfig requested) {
        UdpRedundantConfig serverConfig = configRedundant();
        if (!Udp2rawPayloadSupport.isRedundantEnabled(serverConfig)
                || !Udp2rawPayloadSupport.isRedundantEnabled(requested)) {
            return null;
        }
        return serverConfig;
    }

    private UdpRedundantMode negotiateRedundantMode(SocksConfig config, UdpRedundantMode requested) {
        if (requested != null) {
            return requested;
        }
        UdpRedundantMode mode = config.getUdp2rawRedundantMode();
        return mode != null ? mode : UdpRedundantMode.BIDIRECTIONAL;
    }

    private UdpCompressConfig configCompress() {
        return UdpCompressConfig.fromSocketConfig(server.getConfig());
    }

    private UdpRedundantConfig configRedundant() {
        return UdpRedundantConfig.fromSocketConfig(server.getConfig());
    }

    private TrafficUser resolveTrafficUser(Udp2rawOpenRequest request) {
        String tag = firstNonEmpty(request.getConnectionTag(), request.getTrafficUser());
        if (tag == null || server.getConnectionTagResolver() == null) {
            return TrafficUser.ANONYMOUS;
        }
        AuthResult result = server.getConnectionTagResolver().apply(tag);
        TrafficUser user = result != null ? result.getTrafficUser() : null;
        if (user == null || user.isAnonymous()) {
            DiagnosticMetrics.record("socks.udp2raw.tunnel.traffic.bind.count", 1D, "result=miss");
            return TrafficUser.ANONYMOUS;
        }
        DiagnosticMetrics.record("socks.udp2raw.tunnel.traffic.bind.count", 1D, "result=success");
        return user;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && first.length() > 0) {
            return first;
        }
        return second != null && second.length() > 0 ? second : null;
    }

    private void cleanupIdleTunnels() {
        long now = System.currentTimeMillis();
        for (Udp2rawTunnelContext context : tunnels.values()) {
            if (now >= context.expireAtMillis() && tunnels.remove(context.tunnelId, context)) {
                context.close("idle");
                DiagnosticMetrics.record("socks.udp2raw.tunnel.active.count", tunnels.size(), "action=idle-close");
            }
        }
    }

    @Override
    protected void dispose() {
        cleanupTask.cancel(false);
        for (Udp2rawTunnelContext context : tunnels.values()) {
            context.close("dispose");
        }
        tunnels.clear();
        for (Channel channel : entryChannels) {
            Sockets.closeOnFlushed(channel);
        }
        entryChannels.clear();
        entryAddress = null;
    }

    private static String tunnelId(long sessionHi, long sessionLo) {
        return String.format("%016x%016x", sessionHi, sessionLo);
    }
}
