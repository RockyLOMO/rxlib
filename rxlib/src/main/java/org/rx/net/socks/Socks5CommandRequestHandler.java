package org.rx.net.socks;

import io.netty.channel.*;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.socksx.v5.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.CachePolicy;
import org.rx.core.Tasks;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.*;
import org.rx.net.socks.upstream.SocksTcpUpstream;
import org.rx.net.support.EndpointTracer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@ChannelHandler.Sharable
public class Socks5CommandRequestHandler extends SimpleChannelInboundHandler<DefaultSocks5CommandRequest> {
    public static final Socks5CommandRequestHandler DEFAULT = new Socks5CommandRequestHandler();
    static final DefaultSocks5CommandResponse SUCCESS_CONNECT =
            new DefaultSocks5CommandResponse(Socks5CommandStatus.SUCCESS, Socks5AddressType.IPv4);
    static final ConcurrentMap<Long, CompletableFuture<InetSocketAddress>> FAKE_RECOVERIES = new ConcurrentHashMap<>();

    @Override
    protected void channelRead0(ChannelHandlerContext inbound, DefaultSocks5CommandRequest msg) {
        ChannelPipeline pipeline = inbound.pipeline();
        Channel inCh = inbound.channel();
        pipeline.remove(Socks5CommandRequestDecoder.class.getSimpleName());
        pipeline.remove(this);
        SocksProxyServer server = Sockets.getAttr(inCh, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        // log.debug("socks5[{}] {} {}/{}:{}", server.getConfig().getListenPort(), msg.type(), msg.dstAddrType(), msg.dstAddr(), msg.dstPort());

        if (server.isAuthEnabled() && ProxyManageHandler.get(inbound).getUser().isAnonymous()) {
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FORBIDDEN, msg.dstAddrType())).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        Socks5CommandType commandType = msg.type();
        Socks5AddressType dstAddrType = msg.dstAddrType();
        InetSocketAddress dstEp = org.rx.net.Sockets.newUnresolvedEndpoint(msg.dstAddr(), msg.dstPort());
        String dstEpHost = dstEp.getHostString();
        if (dstEpHost.endsWith(SocksRpcContract.FAKE_HOST_SUFFIX)) {
            Long hash = SocksRpcContract.parseFakeHostHash(dstEpHost);
            InetSocketAddress realEp = hash == null ? null : SocksRpcContract.fakeDict().get(hash);
            if (realEp == null) {
                if (hash != null && server.getFakeEndpointResolver() != null && commandType == Socks5CommandType.CONNECT) {
                    log.debug("socks5[{}] recover dstEp {} miss, request client recovery", config.getListenPort(), dstEp);
                    recoverFakeEndpoint(inbound, server, config, commandType, dstAddrType, dstEp, hash.longValue());
                    return;
                }
                if (commandType != Socks5CommandType.UDP_ASSOCIATE) {
                    failFakeEndpointRecovery(inbound, dstAddrType, config, dstEp);
                    return;
                }
                if (config.isDebug()) {
                    log.debug("socks5[{}] UDP_ASSOCIATE skip fake command dstEp recovery {}", config.getListenPort(), dstEp);
                }
            } else {
                if (config.isDebug()) {
                    log.info("socks5[{}] recover dstEp {}[{}]", config.getListenPort(), dstEp, realEp);
                }
                dstEp = realEp;
            }
        }

        handleCommand(inbound, server, config, commandType, dstAddrType, dstEp);
    }

    private void handleCommand(ChannelHandlerContext inbound, SocksProxyServer server, SocksConfig config,
                               Socks5CommandType commandType, Socks5AddressType dstAddrType, InetSocketAddress dstEp) {
        ChannelPipeline pipeline = inbound.pipeline();
        Channel inCh = inbound.channel();
        InetSocketAddress srcEp = Sockets.getOriginRemoteAddress(inCh);
        ProxyManageHandler manageHandler = ProxyManageHandler.get(inbound);
        if (!server.isAuthEnabled() && manageHandler != null && manageHandler.getUser().isAnonymous()
                && server.getConnectionTagResolver() != null) {
            String connectionTag = SocksConnectionTagRegistry.resolve(inCh);
            AuthResult result = connectionTag == null ? null : server.getConnectionTagResolver().apply(connectionTag);
            if (result != null) {
                manageHandler.setUser(result.getUser(), result.getTrafficUser(), inbound);
            }
        }
        TrafficUser user = manageHandler != null ? manageHandler.getTrafficUser() : TrafficUser.ANONYMOUS;
        TrafficLoginInfo loginInfo = manageHandler != null ? manageHandler.getInfo() : null;
        if (commandType == Socks5CommandType.CONNECT) {
            SocksContext e = SocksContext.getCtx(srcEp, dstEp);
            SocksUserTraffic.attach(e, user, loginInfo);
            try {
                server.publishEvent(server.onTcpRoute, e);
                connect(inCh, dstAddrType, e, null);
            } catch (Exception ex) {
                failTcpRoute(inbound, dstAddrType, config, e, ex);
            }
        } else if (commandType == Socks5CommandType.UDP_ASSOCIATE) {
            log.debug("socks5[{}] UDP_ASSOCIATE {}", config.getListenPort(), dstEp);
            String idleHandlerName = ProxyChannelIdleHandler.class.getSimpleName();
            if (pipeline.get(idleHandlerName) != null) {
                pipeline.remove(idleHandlerName);
            }

            // RFC 1928: create a dedicated per-client UDP relay channel
            final Channel tcpControl = inCh;
            final InetSocketAddress clientTcpAddr = Sockets.getOriginRemoteAddress(tcpControl);
            final InetSocketAddress tcpPeerAddr = Sockets.getRemoteAddress(tcpControl);
            final boolean udp2raw = config.isEnableUdp2raw();
            final boolean redundantClientPeer = shouldTrackRedundantClientPeer(config, clientTcpAddr, tcpPeerAddr, udp2raw);

            InetSocketAddress tcpLocalAddr = Sockets.getLocalAddress(tcpControl);
            SocketAddress udpBindAddr = resolveUdpRelayBindAddress(tcpLocalAddr);
            ChannelFuture udpFuture = Sockets.udpBootstrap(config, ch -> {
                ChannelPipeline p = ch.pipeline();
                if (config.getUdpReadTimeoutSeconds() > 0 || config.getUdpWriteTimeoutSeconds() > 0) {
                    p.addLast(new ProxyChannelIdleHandler(config.getUdpReadTimeoutSeconds(), config.getUdpWriteTimeoutSeconds()));
                }
                p.addLast(udp2raw ? Udp2rawHandler.DEFAULT : SocksUdpRelayHandler.DEFAULT);
            }).attr(SocksContext.SOCKS_SVR, server).bind(udpBindAddr);
            SocksUserTraffic.bind(udpFuture.channel(), user, loginInfo);

            // Pre-set client addr so first packet can be identified
            if (udp2raw) {
                udpFuture.channel().attr(Udp2rawHandler.ATTR_CLIENT_ADDR).set(clientTcpAddr);
            } else {
                udpFuture.channel().attr(SocksUdpRelayHandler.ATTR_CLIENT_ADDR).set(clientTcpAddr);
            }
            udpFuture.channel().attr(UdpRelayAttributes.ATTR_CLIENT_ORIGIN_ADDR).set(clientTcpAddr);
            udpFuture.channel().attr(UdpRelayAttributes.ATTR_CLIENT_LOCKED).set(Boolean.FALSE);
            if (redundantClientPeer) {
                UdpRelayAttributes.initRedundantPeers(udpFuture.channel());
            }
            udpFuture.channel().attr(UdpRelayAttributes.ATTR_REDUNDANT_CLIENT_PEER).set(redundantClientPeer);

            udpFuture.channel().closeFuture().addListener(f -> {
                if (tcpControl.isOpen()) {
                    log.debug("socks5[{}] UDP_ASSOCIATE control closing for {}", config.getListenPort(), clientTcpAddr);
                    tcpControl.close();
                }
            });
            // When TCP control connection closes → close the per-client UDP relay
            tcpControl.closeFuture().addListener(f -> {
                Channel udpRelay = udpFuture.channel();
                if (udpRelay.isOpen()) {
                    log.debug("socks5[{}] UDP_ASSOCIATE relay closing for {}", config.getListenPort(), clientTcpAddr);
                    udpRelay.close();
                }
            });

            // Reply with the UDP relay bind address
            udpFuture.addListener((ChannelFutureListener) f -> {
                if (!f.isSuccess()) {
                    log.warn("socks5[{}] UDP_ASSOCIATE relay bind failed for {}", config.getListenPort(), clientTcpAddr, f.cause());
                    inbound.writeAndFlush(new DefaultSocks5CommandResponse(
                                    Socks5CommandStatus.FAILURE, dstAddrType))
                            .addListener(ChannelFutureListener.CLOSE);
                    return;
                }
                InetSocketAddress udpBindLocalAddr = (InetSocketAddress) f.channel().localAddress();
                InetSocketAddress udpAdvertiseAddr = resolveUdpRelayAdvertiseAddress(tcpLocalAddr, udpBindLocalAddr);
                server.registerUdpRelay(f.channel());
                log.debug("socks5[{}] UDP_ASSOCIATE relay bound {} advertised {} for {}",
                        config.getListenPort(), udpBindLocalAddr, udpAdvertiseAddr, clientTcpAddr);
                // 绑定到 any-local 时，仍向客户端通告 TCP 控制连接可达的本地地址。
                boolean isIpv6 = udpAdvertiseAddr.getAddress() instanceof java.net.Inet6Address;
                Socks5AddressType atyp = isIpv6 ? Socks5AddressType.IPv6 : Socks5AddressType.IPv4;
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(
                        Socks5CommandStatus.SUCCESS, atyp, udpAdvertiseAddr.getAddress().getHostAddress(), udpAdvertiseAddr.getPort()));
            });
        } else {
            log.warn("Command {} not support", commandType);
            inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.COMMAND_UNSUPPORTED, dstAddrType)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    private void recoverFakeEndpoint(ChannelHandlerContext inbound, SocksProxyServer server, SocksConfig config,
                                     Socks5CommandType commandType, Socks5AddressType dstAddrType,
                                     InetSocketAddress fakeEp, long hash) {
        CompletableFuture<InetSocketAddress> future = recoverFakeEndpointAsync(server, hash, fakeEp.getHostString());
        AtomicBoolean completed = new AtomicBoolean();
        Channel channel = inbound.channel();
        channel.eventLoop().schedule(() -> {
            if (completed.compareAndSet(false, true)) {
                if (!channel.isOpen()) {
                    return;
                }
                failFakeEndpointRecovery(inbound, dstAddrType, config, fakeEp);
            }
        }, SocksRpcContract.fakeRecoverWaitMillis(), TimeUnit.MILLISECONDS);
        future.whenComplete((realEp, error) -> channel.eventLoop().execute(() -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            if (!channel.isOpen()) {
                return;
            }
            if (error != null || realEp == null) {
                failFakeEndpointRecovery(inbound, dstAddrType, config, fakeEp);
                return;
            }
            handleCommand(inbound, server, config, commandType, dstAddrType, realEp);
        }));
    }

    private CompletableFuture<InetSocketAddress> recoverFakeEndpointAsync(SocksProxyServer server, long hash, String fakeHost) {
        Long key = Long.valueOf(hash);
        CompletableFuture<InetSocketAddress> current = FAKE_RECOVERIES.get(key);
        if (current != null) {
            return current;
        }
        CompletableFuture<InetSocketAddress> created = Tasks.runAsync(() -> {
            InetSocketAddress recovered = server.recoverFakeEndpoint(hash, fakeHost);
            if (recovered != null) {
                SocksRpcContract.fakeDict().put(key, recovered, CachePolicy.absolute(SocksRpcContract.FAKE_EXPIRE_SECONDS));
                DiagnosticMetrics.record("socks.fake.endpoint.recover.success.count", 1D, "port=" + server.getConfig().getListenPort());
            }
            return recovered;
        });
        CompletableFuture<InetSocketAddress> previous = FAKE_RECOVERIES.putIfAbsent(key, created);
        if (previous != null) {
            return previous;
        }
        created.whenComplete((r, e) -> FAKE_RECOVERIES.remove(key, created));
        return created;
    }

    private void failFakeEndpointRecovery(ChannelHandlerContext inbound, Socks5AddressType dstAddrType,
                                          SocksConfig config, InetSocketAddress fakeEp) {
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.fake.endpoint.recover.failure.count", 1D, "port=" + config.getListenPort());
        }
        log.warn("socks5[{}] recover dstEp {} fail", config.getListenPort(), fakeEp);
        if (!inbound.channel().isOpen()) {
            return;
        }
        inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, dstAddrType))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private void failTcpRoute(ChannelHandlerContext inbound, Socks5AddressType dstAddrType, SocksConfig config,
                              SocksContext e, Exception ex) {
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.tcp.route.failure.count", 1D, "port=" + config.getListenPort());
        }
        log.warn("socks5[{}] TCP route {} => {} fail: {}",
                config.getListenPort(), e.getSource(), e.getFirstDestination(), ex.toString());
        inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, dstAddrType))
                .addListener(ChannelFutureListener.CLOSE);
    }

    public static SocketAddress resolveUdpRelayBindAddress(InetSocketAddress tcpLocalAddr) {
        if (tcpLocalAddr == null) {
            return Sockets.newAnyEndpoint(0);
        }

        InetAddress address = tcpLocalAddr.getAddress();
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()) {
            // 绑定 loopback 后无法向公网目的地发包，Linux 会直接返回 EINVAL。
            return Sockets.newAnyEndpoint(0);
        }
        return new InetSocketAddress(address, 0);
    }

    public static InetSocketAddress resolveUdpRelayAdvertiseAddress(InetSocketAddress tcpLocalAddr, InetSocketAddress udpBindLocalAddr) {
        if (udpBindLocalAddr == null) {
            return null;
        }

        InetAddress bindAddr = udpBindLocalAddr.getAddress();
        if (bindAddr != null && !bindAddr.isAnyLocalAddress()) {
            return udpBindLocalAddr;
        }

        InetAddress tcpAddr = tcpLocalAddr != null ? tcpLocalAddr.getAddress() : null;
        if (tcpAddr != null && !tcpAddr.isAnyLocalAddress()) {
            return new InetSocketAddress(tcpAddr, udpBindLocalAddr.getPort());
        }
        return udpBindLocalAddr;
    }

    public static boolean shouldTrackRedundantClientPeer(SocksConfig config,
            InetSocketAddress clientTcpAddr, InetSocketAddress tcpPeerAddr) {
        return shouldTrackRedundantClientPeer(config, clientTcpAddr, tcpPeerAddr, false);
    }

    public static boolean shouldTrackRedundantClientPeer(SocksConfig config,
            InetSocketAddress clientTcpAddr, InetSocketAddress tcpPeerAddr, boolean udp2raw) {
        return UdpRelayAttributes.shouldTrackClientAsRedundantPeer(config, udp2raw);
    }

    private void connect(Channel inbound, Socks5AddressType dstAddrType, SocksContext e, short[] reconnectionAttempts) {
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        if (e.getUpstream() instanceof SocksTcpUpstream) {
            ((SocksTcpUpstream) e.getUpstream()).prepareDestination();
            if (reconnectionAttempts == null && tryWarmConnect(inbound, dstAddrType, e)) {
                return;
            }
        }
        connectSlow(inbound, dstAddrType, e, reconnectionAttempts);
    }

    private boolean tryWarmConnect(Channel inbound, Socks5AddressType dstAddrType, SocksContext e) {
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        SocksTcpUpstream upstream = (SocksTcpUpstream) e.getUpstream();
        Channel outbound = Socks5UpstreamPoolManager.INSTANCE.borrowWarmChannel(upstream);
        if (outbound == null) {
            return false;
        }
        upstream.bindActiveConnection(outbound);

        ensureFrontendHandlers(inbound, outbound);
        EndpointTracer.TCP.link(Sockets.getOriginRemoteAddress(inbound), outbound);

        Socks5WarmupHandler warmupHandler = outbound.pipeline().get(Socks5WarmupHandler.class);
        if (warmupHandler == null) {
            Sockets.closeOnFlushed(outbound);
            return false;
        }
        warmupHandler.setConnectedCallback(() -> onBackendConnected(inbound, outbound, e));
        ChannelFuture connectFuture = warmupHandler.connect(upstream.getDestination());
        SocksContext.markCtx(inbound, connectFuture, e);
        connectFuture.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                if (DiagnosticMetrics.isEnabled()) {
                    DiagnosticMetrics.record("socks.tcp.warm.fallback.count", 1D, "key=" + upstream.warmPoolKey());
                }
                Sockets.closeOnFlushed(outbound);
                connectSlow(inbound, dstAddrType, e, null);
                return;
            }
            relay(inbound, outbound, dstAddrType, e);
        });
        return true;
    }

    private void connectSlow(Channel inbound, Socks5AddressType dstAddrType, SocksContext e, short[] reconnectionAttempts) {
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        SocketConfig effectiveConfig = e.getUpstream().getConfig();
        if (effectiveConfig == null) {
            effectiveConfig = config;
        }
        SocketAddress connectHint = e.getUpstream().connectAddressHint();
        EventLoopGroup connectGroup = inbound instanceof LocalChannel && !(connectHint instanceof LocalAddress) ? null : inbound.eventLoop();
        ChannelFuture outboundFuture = Sockets.bootstrap(connectGroup, effectiveConfig, connectHint, outbound -> {
            e.getUpstream().initChannel(outbound);
            ensureFrontendHandlers(inbound, outbound);
        }).attr(SocksContext.SOCKS_SVR, server).connect(e.getUpstream().getDestination()).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                if (server.onReconnecting != null) {
                    server.publishEvent(server.onReconnecting, e);
                    short[] attempts = reconnectionAttempts;
                    if (attempts == null) {
                        attempts = new short[]{0};
                    }
                    if (!e.isCancel() && attempts[0] < 16) {
                        attempts[0]++;
                        connect(inbound, dstAddrType, e, attempts);
                        return;
                    }
                }
                if (f.cause() instanceof ConnectTimeoutException) {
                    log.warn("socks5[{}] TCP connect {}[{}] fail\n{}", config.getListenPort(), e.getUpstream().getDestination(), e.getFirstDestination(), f.cause().getMessage());
                } else {
                    log.error("socks5[{}] TCP connect {}[{}] fail", config.getListenPort(), e.getUpstream().getDestination(), e.getFirstDestination(), f.cause());
                }
                inbound.writeAndFlush(new DefaultSocks5CommandResponse(Socks5CommandStatus.FAILURE, dstAddrType)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            Channel outbound = f.channel();
            EndpointTracer.TCP.link(Sockets.getOriginRemoteAddress(inbound), outbound);
            Socks5ClientHandler proxyHandler;
            if (server.cipherRoute(e.getFirstDestination()) && (proxyHandler = outbound.pipeline().get(Socks5ClientHandler.class)) != null) {
                proxyHandler.setHandshakeCallback(() -> {
                    onBackendConnected(inbound, outbound, e);
                    relay(inbound, outbound, dstAddrType, e);
                });
                return;
            }

            relay(inbound, outbound, dstAddrType, e);
        });
        SocksContext.markCtx(inbound, outboundFuture, e);
    }

    private void ensureFrontendHandlers(Channel inbound, Channel outbound) {
        if (inbound.pipeline().get(SocksTcpFrontendRelayHandler.class) == null) {
            inbound.pipeline().addLast(SocksTcpFrontendRelayHandler.DEFAULT);
        }
        if (outbound.pipeline().get(TcpBackpressureHandler.class) == null) {
            TcpBackpressureHandler.install(inbound, outbound);
        }
    }

    private void onBackendConnected(Channel inbound, Channel outbound, SocksContext e) {
        SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
        SocksConfig config = server.config;
        SocketConfig upConf = e.getUpstream().getConfig();
        if (server.cipherRoute(e.getFirstDestination()) && upConf.getTransportFlags().has(TransportFlags.COMPRESS_BOTH)) {
            outbound.attr(SocketConfig.ATTR_CONF).set(upConf);
            Sockets.addBefore(outbound.pipeline(), Sockets.ZIP_DECODER, new CipherDecoder().channelHandlers());
            Sockets.addBefore(outbound.pipeline(), Sockets.ZIP_ENCODER, CipherEncoder.DEFAULT.channelHandlers());
            if (config.isDebug()) {
                log.info("socks5[{}] TCP {} => {} BACKEND_CIPHER", config.getListenPort(), inbound.localAddress(), outbound.remoteAddress());
            }
        }
    }

    private void relay(Channel inbound, Channel outbound, Socks5AddressType dstAddrType, SocksContext e) {
        // initChannel may change dstEp
        InetSocketAddress dstEp = e.getUpstream().getDestination();
        outbound.pipeline().addLast(SocksTcpBackendRelayHandler.DEFAULT);

        inbound.writeAndFlush(SUCCESS_CONNECT).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                Sockets.closeOnFlushed(f.channel());
                return;
            }

            SocksProxyServer server = Sockets.getAttr(inbound, SocksContext.SOCKS_SVR);
            SocksConfig config = server.getConfig();
            if (server.cipherRoute(e.getFirstDestination()) && config.getTransportFlags().has(TransportFlags.COMPRESS_BOTH)) {
                inbound.attr(SocketConfig.ATTR_CONF).set(config);
                Sockets.addBefore(inbound.pipeline(), Sockets.ZIP_DECODER, new CipherDecoder().channelHandlers());
                Sockets.addBefore(inbound.pipeline(), Sockets.ZIP_ENCODER, CipherEncoder.DEFAULT.channelHandlers());
                if (config.isDebug()) {
                    log.info("socks5[{}] TCP {} => {} FRONTEND_CIPHER", config.getListenPort(), inbound.localAddress(), outbound.remoteAddress());
                }
            }
            maybeBypassTcpCompression(inbound, outbound, e, config);
            log.info("socks5[{}] TCP {} => {} connected, dstEp={}[{}]", config.getListenPort(), inbound.localAddress(), outbound.remoteAddress(), dstEp, e.getFirstDestination());
        });
    }

    private void maybeBypassTcpCompression(Channel inbound, Channel outbound, SocksContext e, SocksConfig config) {
        InetSocketAddress dstEp = e.getFirstDestination();
        if (!Sockets.shouldBypassTcpCompression(dstEp)) {
            return;
        }
        if (!Sockets.hasTcpCompressionHandlers(inbound) && !Sockets.hasTcpCompressionHandlers(outbound)) {
            return;
        }

        // The SOCKS command itself has already traversed this tunnel. Removing live zlib handlers here
        // would desync the peer's inflate state and corrupt the rest of the TCP stream.
        if (DiagnosticMetrics.isEnabled()) {
            DiagnosticMetrics.record("socks.tcp.compress.bypass.deferred.count", 1D, "port=" + dstEp.getPort());
        }
        if (config.isDebug()) {
            log.info("socks5[{}] TCP {} => {} BYPASS_DEFERRED dstEp={} reason=live_zlib_stream",
                    config.getListenPort(), inbound.localAddress(), outbound.remoteAddress(), dstEp);
        }
    }
}
