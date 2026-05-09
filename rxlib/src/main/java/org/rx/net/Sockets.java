package org.rx.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNamesBuilder;
import org.bouncycastle.x509.X509V3CertificateGenerator;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.$;
import org.rx.bean.FlagsEnum;
import org.rx.core.*;
import org.rx.core.StringBuilder;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;
import org.rx.io.Bytes;
import org.rx.io.Files;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DoHClient;
import org.rx.net.dns.DnsServer;
import org.rx.net.socks.ShadowsocksConfig;
import org.rx.net.socks.SocksConfig;
import org.rx.net.support.EndpointTracer;
import org.rx.net.support.UnresolvedEndpoint;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;

import javax.net.ssl.KeyManagerFactory;
import javax.security.auth.x500.X500Principal;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.quietly;
import static org.rx.core.Sys.fastCacheKey;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public final class Sockets {
    public enum UdpWriteResult {
        ACCEPTED,
        CHANNEL_INACTIVE,
        CHANNEL_UNWRITABLE,
        UNRESOLVED_RECIPIENT,
        MTU_EXCEEDED,
        PENDING_OVERLIMIT,
        WRITE_THROWN
    }

    /**
     * UDP MTU 探测包允许超过当前本地 MTU guard，真正由路径是否回 ACK 来收敛。
     */
    public static final class UdpMtuProbeDatagramPacket extends DatagramPacket {
        public UdpMtuProbeDatagramPacket(ByteBuf data, InetSocketAddress recipient) {
            super(data, recipient);
        }
    }

    public interface ReactorNames {
        String SHARED_TCP = "_TCP";
        String SHARED_UDP = "_UDP";
        String RPC = "RPC";
    }

    public static final class UdpFinalEgressGuardHandler extends ChannelOutboundHandlerAdapter {
        private final SocketConfig config;
        private final boolean forceBackpressure;

        UdpFinalEgressGuardHandler(SocketConfig config, boolean forceBackpressure) {
            this.config = config;
            this.forceBackpressure = forceBackpressure;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (!(msg instanceof DatagramPacket)) {
                ctx.write(msg, promise);
                return;
            }

            Channel channel = ctx.channel();
            DatagramPacket packet = (DatagramPacket) msg;
            SocketConfig effectiveConfig = udpEffectiveConfig(channel, config);
            String metricPrefix = udpFinalMetricPrefix(effectiveConfig);
            String tags = "path=final-egress";
            int bytes = packet.content().readableBytes();

            if (!channel.isActive()) {
                releaseUdpPacket(packet, metricPrefix, tags, "final-inactive", 0, 0);
                completeDroppedWrite(promise);
                return;
            }

            InetSocketAddress recipient = packet.recipient();
            int limitBytes = udpWriteLimitBytes(channel);
            if (recipient != null && recipient.isUnresolved()) {
                releaseUdpPacket(packet, metricPrefix, tags, "final-unresolved-recipient", 0, limitBytes);
                completeDroppedWrite(promise);
                return;
            }

            int udpMtu = effectiveConfig != null ? Math.max(0, effectiveConfig.getUdpMtu()) : 0;
            if (!(packet instanceof UdpMtuProbeDatagramPacket) && udpMtu > 0 && bytes > udpMtu) {
                releaseUdpPacketByMtu(packet, metricPrefix, tags, bytes, udpMtu);
                completeDroppedWrite(promise);
                return;
            }

            if (!forceBackpressure && udpMtu <= 0) {
                ctx.write(packet, promise);
                return;
            }

            AtomicInteger pendingBytes = udpPendingWriteBytesState(channel);
            int queuedBytes = pendingBytes.addAndGet(bytes);
            if (queuedBytes > limitBytes) {
                pendingBytes.addAndGet(-bytes);
                releaseUdpPacket(packet, metricPrefix, tags, "final-pending-overlimit", queuedBytes, limitBytes);
                completeDroppedWrite(promise);
                return;
            }
            if (!channel.isWritable()) {
                pendingBytes.addAndGet(-bytes);
                releaseUdpPacket(packet, metricPrefix, tags, "final-not-writable", queuedBytes, limitBytes);
                completeDroppedWrite(promise);
                return;
            }

            ChannelPromise writePromise = promise.isVoid() ? ctx.newPromise() : promise;
            writePromise.addListener((ChannelFutureListener) f -> {
                pendingBytes.addAndGet(-bytes);
                if (!f.isSuccess()) {
                    recordUdpMetric(metricPrefix, "drop.count",
                            appendUdpMetricTags(tags, "reason=final-write-fail,limitBucket=" + udpLimitBucket(limitBytes)));
                    log.warn("UDP final write fail channel={} recipient={}", channel, packet.recipient(), f.cause());
                }
            });
            try {
                ctx.write(packet, writePromise);
            } catch (Throwable e) {
                ReferenceCountUtil.release(packet);
                if (!writePromise.tryFailure(e)) {
                    pendingBytes.addAndGet(-bytes);
                }
                recordUdpMetric(metricPrefix, "drop.count",
                        appendUdpMetricTags(tags, "reason=final-write-throw,limitBucket=" + udpLimitBucket(limitBytes)));
                log.warn("UDP final write throw channel={} recipient={}", channel, packet.recipient(), e);
            }
        }

        private static void completeDroppedWrite(ChannelPromise promise) {
            // Final egress drops are metrics-observable; write futures stay successful so UDP callers
            // that only use completion for pending cleanup are not forced onto an exception path.
            if (!promise.isVoid()) {
                promise.trySuccess();
            }
        }
    }

    @ChannelHandler.Sharable
    static class SocketChannelInitializer extends ChannelInitializer<Channel> {
        public static final SocketChannelInitializer DEFAULT = new SocketChannelInitializer();

        @Override
        protected void initChannel(Channel ch) {
            ChannelPipeline p = ch.pipeline();
            SocketConfig config = ch.attr(SocketConfig.ATTR_CONF).get();
            if (config != null && config.isDebug()) {
                p.addLast(DEFAULT_LOG);
            }
            p.addLast(GlobalChannelHandler.DEFAULT);
            getAttr(ch, SocketConfig.ATTR_INIT_FN).accept(ch);
            ch.attr(SocketConfig.ATTR_INIT_FN).set(null);
        }
    }

    public static final String ZIP_ENCODER = "ZIP_ENCODER";
    public static final String ZIP_DECODER = "ZIP_DECODER";
    public static final String UDP_FINAL_EGRESS_GUARD = "UDP_FINAL_EGRESS_GUARD";
    public static final LengthFieldPrepender INT_LENGTH_FIELD_ENCODER = new LengthFieldPrepender(4);
    public static final AttributeKey<InetSocketAddress> ATTR_ORIGIN_REMOTE_ADDR = AttributeKey.valueOf("originRemoteAddr");
    static final AttributeKey<AtomicInteger> ATTR_UDP_PENDING_WRITE_BYTES = AttributeKey.valueOf("udpPendingWriteBytes");
    static final AttributeKey<Integer> ATTR_UDP_WRITE_LIMIT_BYTES = AttributeKey.valueOf("udpWriteLimitBytes");
    static final int DEFAULT_UDP_WRITE_LIMIT_BYTES = 256 * 1024;
    static final Set<Integer> TCP_COMPRESS_BYPASS_PORTS = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
            22, 443, 465, 587, 636, 853, 989, 990, 993, 995, 3389, 8443, 9443)));
    static final String M_0 = "lookupAllHostAddr";
    static final String M_1 = "lookupByName";
    static final LoggingHandler DEFAULT_LOG = new LoggingHandler(LogLevel.INFO);
    static final Map<String, MultithreadEventLoopGroup> reactors = new ConcurrentHashMap<>();
    static String loopbackAddr;
    static volatile DnsServer.ResolveInterceptor nsInterceptor;
    /** 共享 TCP Bootstrap 解析器：走直连 DNS Client。 */
    static volatile AddressResolverGroup<InetSocketAddress> tcpDirectDnsAddressResolverGroup;
    /** 共享 TCP Bootstrap 解析器：走远程 DNS Client。 */
    static volatile AddressResolverGroup<InetSocketAddress> tcpRemoteDnsAddressResolverGroup;

    static AddressResolverGroup<InetSocketAddress> tcpDnsAddressResolverGroup(SocketConfig config) {
        if (!(config instanceof SocksConfig)) {
            return tcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode.DIRECT);
        }

        SocksConfig socksConfig = (SocksConfig) config;
        SocksConfig.TcpAsyncDnsMode mode = socksConfig.getTcpAsyncDnsMode();
        return tcpDnsAddressResolverGroup(mode == null ? SocksConfig.TcpAsyncDnsMode.DIRECT : mode);
    }

    static AddressResolverGroup<InetSocketAddress> tcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode mode) {
        if (mode == null || mode == SocksConfig.TcpAsyncDnsMode.SYSTEM) {
            return DefaultAddressResolverGroup.INSTANCE;
        }
        if (isDirectDnsMode(mode)) {
            AddressResolverGroup<InetSocketAddress> g = tcpDirectDnsAddressResolverGroup;
            if (g != null) {
                return g;
            }
            synchronized (Sockets.class) {
                if (tcpDirectDnsAddressResolverGroup == null) {
                    tcpDirectDnsAddressResolverGroup = buildTcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode.DIRECT);
                }
                return tcpDirectDnsAddressResolverGroup;
            }
        }
        if (isRemoteDnsMode(mode)) {
            AddressResolverGroup<InetSocketAddress> g = tcpRemoteDnsAddressResolverGroup;
            if (g != null) {
                return g;
            }
            synchronized (Sockets.class) {
                if (tcpRemoteDnsAddressResolverGroup == null) {
                    tcpRemoteDnsAddressResolverGroup = buildTcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode.REMOTE);
                }
                return tcpRemoteDnsAddressResolverGroup;
            }
        }
        return DefaultAddressResolverGroup.INSTANCE;
    }

    private static AddressResolverGroup<InetSocketAddress> buildTcpDnsAddressResolverGroup(SocksConfig.TcpAsyncDnsMode mode) {
        DnsClient client;
        Collection<InetSocketAddress> nameServerList;
        if (isDirectDnsMode(mode)) {
            client = DnsClient.directClient();
            nameServerList = DnsClient.directNameServers();
        } else {
            client = DnsClient.remoteClient();
            nameServerList = DnsClient.remoteNameServers();
        }

        DnsNameResolverBuilder nb = new DnsNameResolverBuilder()
                .channelType(udpChannelClass())
                .socketChannelType(tcpChannelClass())
                .nameServerProvider(client.getNameServerProvider())
                .ttl(5, 300)
                .negativeTtl(DnsServer.DEFAULT_NEGATIVE_TTL)
                .queryTimeoutMillis(TimeUnit.SECONDS.toMillis(5))
                .resolvedAddressTypes(ResolvedAddressTypes.IPV4_PREFERRED)
                .recursionDesired(true)
                .maxQueriesPerResolve(8)
                .ndots(1);
        if (log.isInfoEnabled()) {
            log.info("TCP DNS resolver use {} {}", mode, CollectionUtils.isEmpty(nameServerList) ? "platformDefault" : nameServerList);
        }
        return new DnsAddressResolverGroup(nb);
    }

    private static boolean isDirectDnsMode(SocksConfig.TcpAsyncDnsMode mode) {
        return mode == SocksConfig.TcpAsyncDnsMode.DIRECT;
    }

    private static boolean isRemoteDnsMode(SocksConfig.TcpAsyncDnsMode mode) {
        return mode == SocksConfig.TcpAsyncDnsMode.REMOTE;
    }

    public static CompletableFuture<InetSocketAddress> resolveUdpEndpointAsync(InetSocketAddress endpoint, SocketConfig config) {
        CompletableFuture<InetSocketAddress> result = new CompletableFuture<>();
        if (endpoint == null || !endpoint.isUnresolved()) {
            result.complete(endpoint);
            return result;
        }

        String host = endpoint.getHostString();
        io.netty.util.concurrent.Future<InetAddress> resolveFuture = udpDnsClient(config).resolveAsync(host);
        resolveFuture.addListener(f -> {
            if (!f.isSuccess()) {
                result.completeExceptionally(f.cause());
                return;
            }
            InetAddress address = (InetAddress) f.getNow();
            if (address == null) {
                result.completeExceptionally(new UnknownHostException(host));
                return;
            }
            result.complete(new InetSocketAddress(address, endpoint.getPort()));
        });
        return result;
    }

    static DnsClient udpDnsClient(SocketConfig config) {
        if (config instanceof SocksConfig
                && isRemoteDnsMode(((SocksConfig) config).getTcpAsyncDnsMode())) {
            return DnsClient.remoteClient();
        }
        return DnsClient.directClient();
    }
    
    public static LengthFieldBasedFrameDecoder intLengthFieldDecoder() {
        return new LengthFieldBasedFrameDecoder(Constants.MAX_HEAP_BUF_SIZE, 0, 4, 0, 4);
    }

    // region netty
    public static void injectNameService(List<InetSocketAddress> nameServerList) {
        if (CollectionUtils.isEmpty(nameServerList)) {
            throw new InvalidException("Empty server list");
        }
        injectNameService(new DnsClientNameService(nameServerList));
    }

    public static void injectNameService(@NonNull DoHClient client) {
        injectNameService((DnsServer.ResolveInterceptor) client);
    }

    @SneakyThrows
    public static void injectNameService(@NonNull DnsServer.ResolveInterceptor interceptor) {
        DnsServer.ResolveInterceptor old = nsInterceptor;
        if (nsInterceptor == null) {
            synchronized (Sockets.class) {
                if (nsInterceptor == null) {
                    nsInterceptor = interceptor;
                    // Ensure the platform name service is initialized before field replacement.
                    InetAddress.getLoopbackAddress();
                    Class<?> type = InetAddress.class;
                    try {
                        Field field = type.getDeclaredField("nameServices");
                        Reflects.setAccess(field);
                        List<Object> nsList = (List<Object>) field.get(null);
                        nsList.set(0, nsProxy(nsList.get(0)));
                        log.info("nsProxy jdk8 injected"); //jdk8
                    } catch (NoSuchFieldException e) {
                        try {
                            Field field = type.getDeclaredField("nameService");
                            Reflects.setAccess(field);
                            field.set(null, nsProxy(field.get(null)));
                            log.info("nsProxy jdk9-17 injected"); //jdk17
                        } catch (NoSuchFieldException ex) {
                            injectInetAddressResolver(type);
                            log.info("nsProxy jdk18+ injected"); //jdk21
                        }
                    }
                }
            }
        }
        nsInterceptor = interceptor;
        closeOldNameService(old, interceptor);
    }

    private static void closeOldNameService(DnsServer.ResolveInterceptor old, DnsServer.ResolveInterceptor current) {
        if (old == null || old == current || !(old instanceof AutoCloseable)) {
            return;
        }
        quietly(((AutoCloseable) old)::close);
    }

    static final class DnsClientNameService implements DnsServer.ResolveInterceptor, AutoCloseable {
        final DnsClient client;

        DnsClientNameService(List<InetSocketAddress> nameServerList) {
            client = new DnsClient(nameServerList);
        }

        @Override
        public List<InetAddress> resolveHost(InetAddress srcIp, String host) {
            return client.resolveAll(host);
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private static Object nsProxy(Object ns) {
        Class<?> type = ns.getClass();
        InetAddress[] empty = new InetAddress[0];
        return Proxy.newProxyInstance(type.getClassLoader(), type.getInterfaces(), (pObject, method, args) -> {
            if (Strings.hashEquals(method.getName(), M_0)) {
                String host = (String) args[0];
                // null means delegate to the platform resolver; empty means negative answer.
                DnsServer.ResolveInterceptor interceptor = nsInterceptor;
                try {
                    List<InetAddress> addresses = interceptor != null ? interceptor.resolveHost(null, host) : null;
                    if (addresses != null) {
                        if (addresses.isEmpty()) {
                            throw new UnknownHostException(host);
                        }
                        return addresses.toArray(empty);
                    }
                } catch (IllegalStateException | UnknownHostException e) {
                    throw e;
                } catch (Exception e) {
                    log.info("nsProxy error {}", e.getMessage());
                }
            }
            return invokePlatformResolver(method, ns, args);
        });
    }

    @SneakyThrows
    private static void injectInetAddressResolver(Class<?> type) {
        Method resolverMethod = type.getDeclaredMethod("resolver");
        Reflects.setAccess(resolverMethod);
        Object resolver = resolverMethod.invoke(null);
        Field field = type.getDeclaredField("resolver");
        Reflects.setAccess(field);
        field.set(null, inetAddressResolverProxy(resolver));
    }

    private static Object inetAddressResolverProxy(Object resolver) {
        Class<?> resolverInterface = Reflects.loadClass("java.net.spi.InetAddressResolver", true, false);
        if (resolverInterface == null) {
            throw new InvalidException("InetAddressResolver not found");
        }
        return Proxy.newProxyInstance(resolverInterface.getClassLoader(), new Class[]{resolverInterface}, (pObject, method, args) -> {
            if (Strings.hashEquals(method.getName(), M_1)) {
                String host = (String) args[0];
                DnsServer.ResolveInterceptor interceptor = nsInterceptor;
                try {
                    List<InetAddress> addresses = interceptor != null ? interceptor.resolveHost(null, host) : null;
                    if (addresses != null) {
                        if (addresses.isEmpty()) {
                            throw new UnknownHostException(host);
                        }
                        return addresses.stream();
                    }
                } catch (IllegalStateException | UnknownHostException e) {
                    throw e;
                } catch (Exception e) {
                    log.info("nsProxy error {}", e.getMessage());
                }
            }
            return invokePlatformResolver(method, resolver, args);
        });
    }

    private static Object invokePlatformResolver(Method method, Object target, Object[] args) throws Throwable {
        try {
            Reflects.setAccess(method);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    public static EventLoopGroup reactor(String reactorName, boolean isTcp) {
        return reactors.computeIfAbsent(reactorName, k -> {
            int amount = RxConfig.INSTANCE.getNet().getReactorThreadAmount();
            return Epoll.isAvailable() ? new EpollEventLoopGroup(amount) : new NioEventLoopGroup(amount);
        });
    }

    public static ThreadPool newRpcScheduler() {
        int threads = Math.max(4, Constants.CPU_THREADS * 2);
        int queueCapacity = Math.max(1024, Constants.CPU_THREADS * 256);
        return ThreadPool.fixed(ReactorNames.RPC, threads, queueCapacity);
    }

    // Don't use executor
    private static EventLoopGroup newEventLoop(int threadAmount) {
        return Epoll.isAvailable() ? new EpollEventLoopGroup(threadAmount) : new NioEventLoopGroup(threadAmount);
    }

    public static Class<? extends SocketChannel> tcpChannelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    public static Class<? extends DatagramChannel> udpChannelClass() {
        return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }

    public static int reusePortBindCount(SocketConfig config, SocketAddress bindAddress) {
        if (!Epoll.isAvailable()) {
            return 1;
        }
        if (bindAddress != null) {
            if (!(bindAddress instanceof InetSocketAddress)) {
                return 1;
            }
            InetSocketAddress inetAddress = (InetSocketAddress) bindAddress;
            if (inetAddress.getPort() <= 0) {
                return 1;
            }
        }
        int bindCount = config == null ? -1 : config.getReusePortBindCount();
        if (bindCount < 0) {
            bindCount = RxConfig.INSTANCE.getNet().getReusePortBindCount();
        }
        if (bindCount == 0) {
            int cpuThreads = Math.max(1, Constants.CPU_THREADS);
            int reactorThreadAmount = RxConfig.INSTANCE.getNet().getReactorThreadAmount();
            int effectiveReactorThreads = reactorThreadAmount > 0 ? reactorThreadAmount : cpuThreads;
            return Math.max(1, Math.min(effectiveReactorThreads, cpuThreads));
        }
        return Math.max(1, bindCount);
    }

    /**
     * 统一处理 REUSEPORT 多 bind。
     * 仅在 Linux epoll + 固定 Inet 端口 + reusePortBindCount != 1 时创建多 listener。
     */
    public static List<Channel> bindChannels(Bootstrap bootstrap, SocketAddress bindAddress, SocketConfig config) {
        int bindCount = reusePortBindCount(config, bindAddress);
        List<Channel> channels = new ArrayList<>(bindCount);
        try {
            for (int i = 0; i < bindCount; i++) {
                Bootstrap bindBootstrap = i == 0 ? bootstrap : bootstrap.clone();
                if (bindCount > 1) {
                    bindBootstrap.option(EpollChannelOption.SO_REUSEPORT, true);
                }
                ChannelFuture future = bindBootstrap.bind(bindAddress);
                future.addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        log.error("UDP bind {} fail", toString(bindAddress), f.cause());
                    }
                });
                future.syncUninterruptibly();
                channels.add(future.channel());
            }
        } catch (Throwable e) {
            for (Channel channel : channels) {
                channel.close();
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            if (e instanceof Error) {
                throw (Error) e;
            }
            throw new RuntimeException(e);
        }
        if (bindCount > 1) {
            log.info("UDP SO_REUSEPORT enabled bindAddress={} bindCount={}", toString(bindAddress), bindCount);
        }
        return channels;
    }

    /**
     * 统一处理 REUSEPORT 多 bind。
     * 仅在 Linux epoll + 固定 Inet 端口 + reusePortBindCount != 1 时创建多 listener。
     */
    public static List<Channel> bindChannels(ServerBootstrap bootstrap, SocketAddress bindAddress, SocketConfig config) {
        int bindCount = reusePortBindCount(config, bindAddress);
        List<Channel> channels = new ArrayList<>(bindCount);
        try {
            for (int i = 0; i < bindCount; i++) {
                ServerBootstrap bindBootstrap = i == 0 ? bootstrap : bootstrap.clone();
                if (bindCount > 1) {
                    bindBootstrap.option(EpollChannelOption.SO_REUSEPORT, true);
                }
                ChannelFuture future = bindBootstrap.bind(bindAddress);
                future.addListener((ChannelFutureListener) f -> {
                    if (!f.isSuccess()) {
                        log.error("TCP bind {} fail", toString(bindAddress), f.cause());
                    }
                });
                future.syncUninterruptibly();
                channels.add(future.channel());
            }
        } catch (Throwable e) {
            for (Channel channel : channels) {
                channel.close();
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            if (e instanceof Error) {
                throw (Error) e;
            }
            throw new RuntimeException(e);
        }
        if (bindCount > 1) {
            log.info("TCP SO_REUSEPORT enabled bindAddress={} bindCount={}", toString(bindAddress), bindCount);
        }
        return channels;
    }

    // region tcp
    public static ServerBootstrap serverBootstrap(BiAction<SocketChannel> initChannel) {
        return serverBootstrap(null, initChannel);
    }

    public static ServerBootstrap serverBootstrap(SocketConfig config, BiAction<SocketChannel> initChannel) {
        if (config == null) {
            config = SocketConfig.EMPTY;
        }
        String rn = config.getReactorName();
        if (rn == null || Strings.hashEquals(rn, ReactorNames.SHARED_UDP)) {
            rn = ReactorNames.SHARED_TCP;
        }

        OptimalSettings op = ifNull(config.getOptimalSettings(), OptimalSettings.EMPTY);
        op.calculate();
        int backlog = op.backlog;
        WriteBufferWaterMark writeBufferWaterMark = op.writeBufferWaterMark;
        AdaptiveRecvByteBufAllocator recvByteBufAllocator = op.recvByteBufAllocator;
        int connectTimeoutMillis = config.getConnectTimeoutMillis();
        // REUSEPORT 多 bind 需要至少匹配的 boss 数量，才能让监听 socket 分散到多个 accept loop。
        final int bossThreadAmount = reusePortBindCount(config, null);
        ServerBootstrap b = new ServerBootstrap()
                .group(newEventLoop(bossThreadAmount), reactor(rn, true))
                .channel(Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, backlog)
                // .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .childAttr(SocketConfig.ATTR_CONF, config);
        // When you call .handler()/.childHandler() multiple times on a Netty Bootstrap, only the last handler set is actually used.
        // parent channel bind log
        b.handler(GlobalChannelHandler.DEFAULT);
        if (writeBufferWaterMark != null) {
            b.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark);
        }
        if (initChannel != null) {
            b.attr(SocketConfig.ATTR_INIT_FN, (BiAction) initChannel);
            b.childHandler(SocketChannelInitializer.DEFAULT);
        }
        return b;
    }

    public static void closeBootstrap(ServerBootstrap bootstrap) {
        if (bootstrap == null) {
            return;
        }

        ServerBootstrapConfig config = bootstrap.config();
        EventLoopGroup group = config.group();
        if (group != null) {
            group.shutdownGracefully();
        }
        group = config.childGroup();
        if (group != null && !reactors.containsValue(group)) {
            group.shutdownGracefully();
        }
    }

    public static Bootstrap bootstrap(SocketConfig config, BiAction<SocketChannel> initChannel) {
        return bootstrap(null, config, initChannel);
    }

    public static Bootstrap bootstrap(EventLoopGroup eventLoopGroup, SocketConfig config, BiAction<SocketChannel> initChannel) {
        return bootstrap(eventLoopGroup, config, null, initChannel == null ? null : ch -> initChannel.accept((SocketChannel) ch));
    }

    public static Bootstrap bootstrap(SocketConfig config, SocketAddress connectHint, BiAction<Channel> initChannel) {
        return bootstrap(null, config, connectHint, initChannel);
    }

    public static Bootstrap bootstrap(EventLoopGroup eventLoopGroup, SocketConfig config, SocketAddress connectHint, BiAction<Channel> initChannel) {
        if (config == null) {
            config = SocketConfig.EMPTY;
        }
        String rn = config.getReactorName();
        if (rn == null || Strings.hashEquals(rn, ReactorNames.SHARED_UDP)) {
            rn = ReactorNames.SHARED_TCP;
        }
        if (eventLoopGroup == null) {
            eventLoopGroup = reactor(rn, true);
        }

        OptimalSettings op = ifNull(config.getOptimalSettings(), OptimalSettings.EMPTY);
        op.calculate();
        WriteBufferWaterMark writeBufferWaterMark = op.writeBufferWaterMark;
        AdaptiveRecvByteBufAllocator recvByteBufAllocator = op.recvByteBufAllocator;
        int connectTimeoutMillis = config.getConnectTimeoutMillis();
        Bootstrap b = new Bootstrap().group(eventLoopGroup);
        b.attr(SocketConfig.ATTR_CONF, config);
        if (connectHint instanceof LocalAddress) {
            b.channel(LocalChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis);
        } else {
            b.channel(tcpChannelClass())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator);
            b.resolver(tcpDnsAddressResolverGroup(config));
            if (writeBufferWaterMark != null) {
                b.option(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark);
            }
        }
        if (initChannel != null) {
            b.attr(SocketConfig.ATTR_INIT_FN, (BiAction) initChannel);
            b.handler(SocketChannelInitializer.DEFAULT);
        }
        return b;
    }

    public static void addBefore(ChannelPipeline pipeline, String baseName, ChannelHandler... handlers) {
        for (int i = 0; i < handlers.length; i++) {
            ChannelHandler handler = handlers[i];
            pipeline.addBefore(baseName, handler.getClass().getSimpleName(), handler);
        }
    }

    public static void addAfter(ChannelPipeline pipeline, String baseName, ChannelHandler... handlers) {
        for (int i = handlers.length - 1; i > -1; i--) {
            ChannelHandler handler = handlers[i];
            pipeline.addAfter(baseName, handler.getClass().getSimpleName(), handler);
        }
    }

    static ChannelHandler newTcpCompressionEncoder(SocketConfig config, ZlibWrapper zlib) {
        int level = config != null ? config.getTcpCompressionLevel() : -1;
        return level < 0 ? ZlibCodecFactory.newZlibEncoder(zlib) : ZlibCodecFactory.newZlibEncoder(zlib, level);
    }

    static ChannelHandler newTcpClientCompressionEncoder(Channel channel, SocketConfig config, ZlibWrapper zlib) {
        // 客户端 DNS/connect 失败时 Channel 还未 active，避免 JdkZlibEncoder.close() flush trailer。
        return channel != null && channel.isActive()
                ? newTcpCompressionEncoder(config, zlib)
                : new ActiveTcpCompressionEncoderInstaller(config, zlib);
    }

    static final class ActiveTcpCompressionEncoderInstaller extends ChannelInboundHandlerAdapter {
        final SocketConfig config;
        final ZlibWrapper zlib;

        ActiveTcpCompressionEncoderInstaller(SocketConfig config, ZlibWrapper zlib) {
            this.config = config;
            this.zlib = zlib;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            ChannelPipeline pipeline = ctx.pipeline();
            ChannelHandlerContext current = pipeline.context(this);
            if (current != null) {
                pipeline.replace(current.name(), current.name(), newTcpCompressionEncoder(config, zlib));
            }
            ctx.fireChannelActive();
        }
    }

    public static boolean hasTcpCompressionHandlers(Channel channel) {
        if (channel == null) {
            return false;
        }
        ChannelPipeline pipeline = channel.pipeline();
        return pipeline.get(ZIP_ENCODER) != null || pipeline.get(ZIP_DECODER) != null;
    }

    public static boolean removeTcpCompressionHandlers(Channel channel) {
        if (channel == null) {
            return false;
        }
        ChannelPipeline pipeline = channel.pipeline();
        boolean removed = false;
        if (pipeline.get(ZIP_ENCODER) != null) {
            pipeline.remove(ZIP_ENCODER);
            removed = true;
        }
        if (pipeline.get(ZIP_DECODER) != null) {
            pipeline.remove(ZIP_DECODER);
            removed = true;
        }
        return removed;
    }

    public static boolean shouldBypassTcpCompression(UnresolvedEndpoint dstEp) {
        return dstEp != null && TCP_COMPRESS_BYPASS_PORTS.contains(dstEp.getPort());
    }

    @SneakyThrows
    public static Channel addTcpServerHandler(Channel channel, SocketConfig config) {
        FlagsEnum<TransportFlags> flags = config.getTransportFlags();
        if (flags == null) {
            return channel;
        }

        ZlibWrapper zlib = ZlibWrapper.ZLIB;
        // 入站事件（如数据读取、连接建立等）由 ChannelInboundHandler 处理，传播方向是从 pipeline 的 head 到 tail。
        // 出站事件（如数据写入、连接关闭等）由 ChannelOutboundHandler 处理，传播方向是从 pipeline 的 tail 到 head。
        ChannelPipeline pipeline = channel.pipeline();
        if (flags.has(TransportFlags.TLS)) {
            pipeline.addLast(getSelfSignedTls().newHandler(channel.alloc()));
        }

        // 先压缩再加密
        // 支持LengthField?
        boolean g = flags.has(TransportFlags.GFW);
        if (!g) {
            if (flags.has(TransportFlags.COMPRESS_READ)) {
                pipeline.addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(zlib));
            }
            if (flags.has(TransportFlags.COMPRESS_WRITE)) {
                pipeline.addLast(ZIP_ENCODER, newTcpCompressionEncoder(config, zlib));
            }
        }

        boolean hasCipherR = flags.has(TransportFlags.CIPHER_READ),
                hasCipherW = flags.has(TransportFlags.CIPHER_WRITE);
        if (hasCipherR || hasCipherW) {
            if (config.getCipherKey() == null) {
                throw new InvalidException("Cipher key is empty");
            }
            channel.attr(SocketConfig.ATTR_CONF).set(config);
        }
        if (hasCipherR) {
            pipeline.addLast(new CipherDecoder().channelHandlers());
        }
        if (hasCipherW) {
            pipeline.addLast(CipherEncoder.DEFAULT.channelHandlers());
        }

        if (flags.has(TransportFlags.HTTP_PSEUDO_READ)) {
            pipeline.addLast(new HttpPseudoHeaderDecoder());
        }
        if (flags.has(TransportFlags.HTTP_PSEUDO_WRITE)) {
            pipeline.addLast(HttpPseudoHeaderEncoder.DEFAULT);
        }

        if (g) {
            if (flags.has(TransportFlags.COMPRESS_READ)) {
                pipeline.addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(zlib));
            }
            if (flags.has(TransportFlags.COMPRESS_WRITE)) {
                pipeline.addLast(ZIP_ENCODER, newTcpCompressionEncoder(config, zlib));
            }
        }
        DiagnosticMetrics.installNetIoHandler(pipeline, DiagnosticMetrics.netComponent(config,
                config instanceof SocksConfig ? DiagnosticMetrics.NET_SOCKS_SERVER : DiagnosticMetrics.NET_TRANSPORT_SERVER));
        dumpPipeline("server", channel);
        return channel;
    }

    public static Channel addTcpClientHandler(Channel channel, SocketConfig config) {
        return addTcpClientHandler(channel, config, null);
    }

    @SneakyThrows
    public static Channel addTcpClientHandler(Channel channel, SocketConfig config, InetSocketAddress SNISpoofing) {
        FlagsEnum<TransportFlags> flags = config.getTransportFlags();
        if (flags == null) {
            return channel;
        }

        ZlibWrapper zlib = ZlibWrapper.ZLIB;
        ChannelPipeline pipeline = channel.pipeline();
        if (flags.has(TransportFlags.TLS)) {
            SslContext tls = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            if (SNISpoofing == null) {
                SNISpoofing = Sockets.parseEndpoint("qq.com:443");
            }
            pipeline.addLast(tls.newHandler(channel.alloc(), SNISpoofing.getHostString(), SNISpoofing.getPort()));
        }

        boolean g = flags.has(TransportFlags.GFW);
        if (!g) {
            if (flags.has(TransportFlags.COMPRESS_READ)) {
                pipeline.addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(zlib));
            }
            if (flags.has(TransportFlags.COMPRESS_WRITE)) {
                pipeline.addLast(ZIP_ENCODER, newTcpClientCompressionEncoder(channel, config, zlib));
            }
        }

        boolean hasCipherR = flags.has(TransportFlags.CIPHER_READ),
                hasCipherW = flags.has(TransportFlags.CIPHER_WRITE);
        if (hasCipherR || hasCipherW) {
            if (config.getCipherKey() == null) {
                throw new InvalidException("Cipher key is empty");
            }
            channel.attr(SocketConfig.ATTR_CONF).set(config);
        }
        if (hasCipherR) {
            pipeline.addLast(new CipherDecoder().channelHandlers());
        }
        if (hasCipherW) {
            pipeline.addLast(CipherEncoder.DEFAULT.channelHandlers());
        }

        if (flags.has(TransportFlags.HTTP_PSEUDO_READ)) {
            pipeline.addLast(new HttpPseudoHeaderDecoder());
        }
        if (flags.has(TransportFlags.HTTP_PSEUDO_WRITE)) {
            pipeline.addLast(HttpPseudoHeaderEncoder.DEFAULT);
        }

        if (g) {
            if (flags.has(TransportFlags.COMPRESS_READ)) {
                pipeline.addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(zlib));
            }
            if (flags.has(TransportFlags.COMPRESS_WRITE)) {
                pipeline.addLast(ZIP_ENCODER, newTcpClientCompressionEncoder(channel, config, zlib));
            }
        }
        DiagnosticMetrics.installNetIoHandler(pipeline, DiagnosticMetrics.netComponent(config,
                config instanceof SocksConfig ? DiagnosticMetrics.NET_SOCKS_CLIENT : DiagnosticMetrics.NET_TRANSPORT_CLIENT));
        dumpPipeline("client", channel);
        return channel;
    }

    @SneakyThrows
    public static SslContext getSelfSignedTls() {
        SecureRandom random = new SecureRandom();
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048, random);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        X509Certificate certificate = newSelfSignedCertificate(keyPair, random);
        return SslContextBuilder.forServer(keyPair.getPrivate(), certificate).build();
    }

    @SneakyThrows
    public static SslContext sslContext(String certificatePath, String certificatePassword) {
        if (Strings.isEmpty(certificatePath)) {
            log.warn("TLS enabled without certificate, use localhost self-signed certificate");
            return getSelfSignedTls();
        }
        File file = new File(certificatePath);
        if (!file.exists()) {
            log.warn("Certificate file not found: {}", certificatePath);
            return getSelfSignedTls();
        }

        if (certificatePath.endsWith(".pfx") || certificatePath.endsWith(".p12") || certificatePath.endsWith(".jks")) {
            KeyStore keyStore = KeyStore.getInstance(certificatePath.endsWith(".jks") ? "JKS" : "PKCS12");
            try (InputStream is = java.nio.file.Files.newInputStream(file.toPath())) {
                keyStore.load(is, certificatePassword == null ? null : certificatePassword.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, certificatePassword == null ? null : certificatePassword.toCharArray());
            return SslContextBuilder.forServer(kmf).build();
        }
        return SslContextBuilder.forServer(file, file, certificatePassword).build();
    }

    @SneakyThrows
    static X509Certificate newSelfSignedCertificate(KeyPair keyPair, SecureRandom random) {
        long now = System.currentTimeMillis();
        X500Principal principal = new X500Principal("CN=localhost");
        X509V3CertificateGenerator generator = new X509V3CertificateGenerator();
        generator.setSerialNumber(new BigInteger(64, random).abs().add(BigInteger.ONE));
        generator.setIssuerDN(principal);
        generator.setSubjectDN(principal);
        generator.setNotBefore(new Date(now - TimeUnit.DAYS.toMillis(1)));
        generator.setNotAfter(new Date(now + TimeUnit.DAYS.toMillis(3650)));
        generator.setPublicKey(keyPair.getPublic());
        generator.setSignatureAlgorithm("SHA256withRSA");

        GeneralNamesBuilder subjectAltNames = new GeneralNamesBuilder();
        subjectAltNames.addName(new GeneralName(GeneralName.dNSName, "localhost"));
        addLocalHostName(subjectAltNames);
        subjectAltNames.addName(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        subjectAltNames.addName(new GeneralName(GeneralName.iPAddress, "::1"));
        generator.addExtension(Extension.subjectAlternativeName, false, subjectAltNames.build());
        return generator.generate(keyPair.getPrivate(), random);
    }

    private static void addLocalHostName(GeneralNamesBuilder subjectAltNames) {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            if (!Strings.isEmpty(hostName) && !Strings.hashEquals(hostName, "localhost")) {
                subjectAltNames.addName(new GeneralName(GeneralName.dNSName, hostName));
            }
        } catch (Throwable e) {
            log.debug("local host name unavailable", e);
        }
    }

    public static void dumpPipeline(String prefix, Channel channel) {
        if (log.isDebugEnabled()) {
            ChannelPipeline pipeline = channel.pipeline();
            StringBuilder in = new StringBuilder();
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, ChannelHandler> entry : pipeline) {
                String handlerName = entry.getKey();
                ChannelHandler handler = entry.getValue();
                if (handler instanceof ChannelInboundHandler) {
                    in.append(handlerName).append(", ");
                    // in.appendMessageFormat(" {}[{}]", handlerName, handler);
                }
                if (handler instanceof ChannelOutboundHandler) {
                    out.append(handlerName).append(", ");
                    // out.appendMessageFormat(" {}[{}]", handlerName, handler);
                }
            }
            if (!in.isEmpty()) {
                in.setLength(in.length() - 2);
            }
            if (!out.isEmpty()) {
                out.setLength(out.length() - 2);
            }
            log.debug("Dump pipeline {}\nInbound: {}\nOutbound: {}", prefix, in, out);
        }
    }

    public static <T> T getAttr(Channel chnl, AttributeKey<T> key) {
        return getAttr(chnl, key, true);
    }

    public static <T> T getAttr(Channel chnl, AttributeKey<T> key, boolean throwOnEmpty) {
        T v = chnl.attr(key).get();
        Channel parent;
        if (v == null && (parent = chnl.parent()) != null) {
            v = parent.attr(key).get();
            if (v == null && throwOnEmpty) {
                throw new InvalidException("Attr {} not exist", key);
            }
        }
        return v;
    }
    // endregion

    public static Bootstrap udpBootstrap(SocketConfig config, BiAction<DatagramChannel> initChannel) {
        return udpBootstrap(config, false, initChannel);
    }

    // BlockingOperationException 因为执行sync()-wait和notify的是同一个EventLoop中的线程
    // DefaultDatagramChannelConfig
    @SneakyThrows
    public static Bootstrap udpBootstrap(SocketConfig config, boolean multicast, BiAction<DatagramChannel> initChannel) {
        if (config == null) {
            config = SocketConfig.EMPTY;
        }
        String rn = config.getReactorName();
        if (rn == null || Strings.hashEquals(rn, ReactorNames.SHARED_TCP)) {
            rn = ReactorNames.SHARED_UDP;
        }

        OptimalSettings op = ifNull(config.getOptimalSettings(), OptimalSettings.EMPTY);
        op.calculate();
        AdaptiveRecvByteBufAllocator recvByteBufAllocator = op.recvByteBufAllocator;

        // writeBufferWaterMark UDP无效
        Bootstrap b = new Bootstrap()
                .group(reactor(rn, false))
                .channel(Sockets.udpChannelClass())
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .attr(SocketConfig.ATTR_CONF, config);
        if (multicast) {
            NetworkInterface mif = NetworkInterface.getByInetAddress(Sockets.getLocalAddress(true));
            b.option(ChannelOption.SO_BROADCAST, true)
                    .option(ChannelOption.IP_MULTICAST_IF, mif);
        }
        final SocketConfig finalConfig = config;
        b.attr(SocketConfig.ATTR_INIT_FN, (BiAction<Channel>) ch -> {
            if (finalConfig instanceof SocksConfig) {
                SocksConfig socksConfig = (SocksConfig) finalConfig;
                addUdpFinalEgressGuard(ch.pipeline(), socksConfig, isUdpRedundantEnabled(socksConfig));
                addUdpOptimizationHandlers(ch.pipeline(), socksConfig);
            } else {
                addUdpFinalEgressGuard(ch.pipeline(), finalConfig, false);
            }
            DiagnosticMetrics.installNetIoHandler(ch.pipeline(), finalConfig instanceof SocksConfig
                    ? DiagnosticMetrics.NET_SOCKS_CLIENT : DiagnosticMetrics.NET_TRANSPORT_CLIENT);
            if (initChannel != null) {
                initChannel.accept((DatagramChannel) ch);
            }
        });
        b.handler(SocketChannelInitializer.DEFAULT);
        return b;
    }

    /**
     * 向 pipeline 添加 UDP 多倍发包的 Decoder（入站去重）和 Encoder（出站冗余发送）。
     * 支持静态模式和自适应模式。
     * <p>
     * 通过 {@link #udpBootstrap(SocketConfig, BiAction)} 创建 DatagramChannel 时会自动调用；
     * 仅在自行组装 pipeline 时才需要直接调用本方法。
     */
    public static void addRedundantHandlers(ChannelPipeline pipeline, SocksConfig config) {
        addUdpOptimizationHandlers(pipeline, config);
    }

    /**
     * 向 pipeline 添加 UDP 压缩 / 多倍发包优化 Handler。
     * <p>
     * 必须在业务/protocol outbound handler 之前安装。最终出站顺序为：
     * 业务写出 -> 压缩 -> 多倍发送 -> final egress guard -> transport。
     */
    public static void addUdpOptimizationHandlers(ChannelPipeline pipeline, SocksConfig config) {
        boolean compressEnabled = config.isUdpCompressEnabled();
        boolean redundantEnabled = isUdpRedundantEnabled(config);
        addUdpFinalEgressGuard(pipeline, config, redundantEnabled);
        if (!compressEnabled && !redundantEnabled) {
            return;
        }

        int multiplier = config.getUdpRedundantMultiplier();
        if (redundantEnabled) {
            org.rx.net.socks.UdpRedundantMultiplierResolver resolver = config.buildUdpRedundantMultiplierResolver();
            if (config.isUdpRedundantAdaptive()) {
                org.rx.net.socks.UdpRedundantStats stats = new org.rx.net.socks.UdpRedundantStats(
                        multiplier, // 尊重用户配置的初始倍率
                        config.getUdpRedundantMinMultiplier(),
                        config.getUdpRedundantMaxMultiplier(),
                        config.getUdpRedundantIntervalMicros(),
                        config.getUdpRedundantLossThresholdHigh(),
                        config.getUdpRedundantLossThresholdLow(),
                        config.getUdpRedundantStablePeriods());
                pipeline.addLast(org.rx.net.socks.UdpRedundantDecoder.class.getSimpleName(), new org.rx.net.socks.UdpRedundantDecoder(stats));
                if (compressEnabled) {
                    pipeline.addLast(org.rx.net.socks.UdpCompressDecoder.class.getSimpleName(),
                            new org.rx.net.socks.UdpCompressDecoder());
                }
                pipeline.addLast(org.rx.net.socks.UdpRedundantEncoder.class.getSimpleName(), new org.rx.net.socks.UdpRedundantEncoder(stats, resolver));
            } else {
                pipeline.addLast(org.rx.net.socks.UdpRedundantDecoder.class.getSimpleName(), new org.rx.net.socks.UdpRedundantDecoder());
                if (compressEnabled) {
                    pipeline.addLast(org.rx.net.socks.UdpCompressDecoder.class.getSimpleName(),
                            new org.rx.net.socks.UdpCompressDecoder());
                }
                pipeline.addLast(org.rx.net.socks.UdpRedundantEncoder.class.getSimpleName(),
                        new org.rx.net.socks.UdpRedundantEncoder(multiplier, config.getUdpRedundantIntervalMicros(), resolver));
            }
        } else if (compressEnabled) {
            pipeline.addLast(org.rx.net.socks.UdpCompressDecoder.class.getSimpleName(),
                    new org.rx.net.socks.UdpCompressDecoder());
        }

        if (compressEnabled) {
            pipeline.addLast(org.rx.net.socks.UdpCompressEncoder.class.getSimpleName(),
                    new org.rx.net.socks.UdpCompressEncoder(org.rx.net.socks.UdpCompressConfig.fromSocksConfig(config)));
        }
    }

    public static void addUdpFinalEgressGuard(ChannelPipeline pipeline, SocketConfig config) {
        addUdpFinalEgressGuard(pipeline, config, false);
    }

    static void addUdpFinalEgressGuard(ChannelPipeline pipeline, SocketConfig config, boolean forceBackpressure) {
        if (pipeline.get(UDP_FINAL_EGRESS_GUARD) != null || pipeline.get(UdpFinalEgressGuardHandler.class) != null) {
            return;
        }
        if (!shouldInstallUdpFinalEgressGuard(config, forceBackpressure)) {
            return;
        }
        pipeline.addLast(UDP_FINAL_EGRESS_GUARD, new UdpFinalEgressGuardHandler(config, forceBackpressure));
    }

    private static boolean shouldInstallUdpFinalEgressGuard(SocketConfig config, boolean forceBackpressure) {
        return config != null && (config.getUdpMtu() > 0 || forceBackpressure);
    }

    private static boolean isUdpRedundantEnabled(SocksConfig config) {
        return config != null
                && (config.getUdpRedundantMultiplier() > 1
                || config.isUdpRedundantAdaptive()
                || config.hasUdpRedundantDestinationRules());
    }

    public static void writeAndFlush(Channel channel, @NonNull Queue<?> packs) {
        EventLoop eventLoop = channel.eventLoop();
        if (eventLoop.inEventLoop()) {
            Object pack;
            while ((pack = packs.poll()) != null) {
                channel.write(pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            }
            channel.flush();
            return;
        }

        eventLoop.execute(() -> {
            Object pack;
            while ((pack = packs.poll()) != null) {
                channel.write(pack).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            }
            channel.flush();
        });
    }

    /**
     * UDP 没有 TCP 那种传输层背压，这里做的是应用层写侧过载保护：
     * 1) inactive / notWritable 直接丢弃
     * 2) 基于每个 channel 的待完成写入字节数做软上限保护
     */
    public static UdpWriteResult writeUdp(Channel channel, DatagramPacket packet, String metricPrefix, String tags) {
        return writeUdp(channel, packet, metricPrefix, tags, null);
    }

    public static UdpWriteResult writeUdp(Channel channel, DatagramPacket packet, String metricPrefix, String tags,
                                          ChannelFutureListener completionListener) {
        return writeUdp(channel, packet, null, metricPrefix, tags, completionListener);
    }

    public static UdpWriteResult writeUdp(Channel channel, DatagramPacket packet, SocketConfig config,
                                          String metricPrefix, String tags) {
        return writeUdp(channel, packet, config, metricPrefix, tags, null);
    }

    public static UdpWriteResult writeUdp(Channel channel, DatagramPacket packet, SocketConfig config,
                                          String metricPrefix, String tags,
                                          ChannelFutureListener completionListener) {
        if (packet == null) {
            return UdpWriteResult.WRITE_THROWN;
        }
        if (channel == null) {
            releaseUdpPacket(packet, metricPrefix, tags, "null-channel", 0, 0);
            return UdpWriteResult.WRITE_THROWN;
        }

        int bytes = packet.content().readableBytes();
        boolean finalGuard = hasUdpFinalEgressGuard(channel);
        if (!channel.isActive()) {
            releaseUdpPacket(packet, metricPrefix, tags, "inactive", 0, 0);
            return UdpWriteResult.CHANNEL_INACTIVE;
        }

        InetSocketAddress recipient = packet.recipient();
        if (recipient != null && recipient.isUnresolved()) {
            releaseUdpPacket(packet, metricPrefix, tags, "unresolved-recipient", 0, udpWriteLimitBytes(channel));
            log.warn("UDP write drop unresolved recipient channel={} recipient={}", channel, recipient);
            return UdpWriteResult.UNRESOLVED_RECIPIENT;
        }

        AtomicInteger pendingBytes = null;
        int limitBytes = 0;
        int queuedBytes = 0;
        if (!finalGuard) {
            int udpMtu = udpMtu(channel, config);
            if (!(packet instanceof UdpMtuProbeDatagramPacket) && udpMtu > 0 && bytes > udpMtu) {
                releaseUdpPacketByMtu(packet, metricPrefix, tags, bytes, udpMtu);
                return UdpWriteResult.MTU_EXCEEDED;
            }

            pendingBytes = udpPendingWriteBytesState(channel);
            queuedBytes = pendingBytes.addAndGet(bytes);
            limitBytes = udpWriteLimitBytes(channel);
            if (queuedBytes > limitBytes) {
                pendingBytes.addAndGet(-bytes);
                releaseUdpPacket(packet, metricPrefix, tags, "pending-overlimit", queuedBytes, limitBytes);
                return UdpWriteResult.PENDING_OVERLIMIT;
            }
            if (!channel.isWritable()) {
                pendingBytes.addAndGet(-bytes);
                releaseUdpPacket(packet, metricPrefix, tags, "not-writable", queuedBytes, limitBytes);
                return UdpWriteResult.CHANNEL_UNWRITABLE;
            }
        }

        try {
            AtomicInteger finalPendingBytes = pendingBytes;
            int finalLimitBytes = limitBytes;
            channel.writeAndFlush(packet).addListener((ChannelFutureListener) f -> {
                if (finalPendingBytes != null) {
                    finalPendingBytes.addAndGet(-bytes);
                }
                if (!f.isSuccess() && !finalGuard) {
                    recordUdpMetric(metricPrefix, "drop.count",
                            appendUdpMetricTags(tags, "reason=write-fail,limitBucket=" + udpLimitBucket(finalLimitBytes)));
                    log.warn("UDP write fail channel={} recipient={}", channel, packet.recipient(), f.cause());
                }
                if (completionListener != null) {
                    try {
                        completionListener.operationComplete(f);
                    } catch (Exception e) {
                        log.warn("UDP write completion listener failed channel={}", channel, e);
                    }
                }
            });
            return UdpWriteResult.ACCEPTED;
        } catch (Throwable e) {
            if (pendingBytes != null) {
                pendingBytes.addAndGet(-bytes);
            }
            releaseUdpPacket(packet, metricPrefix, tags, "write-throw", queuedBytes, limitBytes);
            log.warn("UDP write throw channel={} recipient={}", channel, packet.recipient(), e);
            return UdpWriteResult.WRITE_THROWN;
        }
    }

    static int udpPendingWriteBytes(Channel channel) {
        AtomicInteger state = channel.attr(ATTR_UDP_PENDING_WRITE_BYTES).get();
        return state == null ? 0 : Math.max(0, state.get());
    }

    static int udpWriteLimitBytes(Channel channel) {
        Integer override = channel.attr(ATTR_UDP_WRITE_LIMIT_BYTES).get();
        if (override != null && override > 0) {
            return override;
        }

        SocketConfig config = channel.attr(SocketConfig.ATTR_CONF).get();
        if (config != null && config.getUdpWriteLimitBytes() > 0) {
            return config.getUdpWriteLimitBytes();
        }
        OptimalSettings op = config == null ? OptimalSettings.EMPTY : ifNull(config.getOptimalSettings(), OptimalSettings.EMPTY);
        WriteBufferWaterMark waterMark = op.writeBufferWaterMark;
        if (waterMark != null) {
            return Math.max(DEFAULT_UDP_WRITE_LIMIT_BYTES, waterMark.high());
        }
        return DEFAULT_UDP_WRITE_LIMIT_BYTES;
    }

    private static boolean hasUdpFinalEgressGuard(Channel channel) {
        return channel.pipeline().get(UdpFinalEgressGuardHandler.class) != null
                || channel.pipeline().get(UDP_FINAL_EGRESS_GUARD) != null;
    }

    private static int udpMtu(Channel channel, SocketConfig override) {
        SocketConfig config = udpEffectiveConfig(channel, override);
        return config != null ? Math.max(0, config.getUdpMtu()) : 0;
    }

    private static SocketConfig udpEffectiveConfig(Channel channel, SocketConfig override) {
        return override != null ? override : channel.attr(SocketConfig.ATTR_CONF).get();
    }

    private static String udpFinalMetricPrefix(SocketConfig config) {
        if (config instanceof ShadowsocksConfig) {
            return "ss.udp";
        }
        if (config instanceof SocksConfig) {
            return "socks.udp";
        }
        return "udp";
    }

    private static AtomicInteger udpPendingWriteBytesState(Channel channel) {
        AtomicInteger state = channel.attr(ATTR_UDP_PENDING_WRITE_BYTES).get();
        if (state != null) {
            return state;
        }
        AtomicInteger newState = new AtomicInteger();
        AtomicInteger oldState = channel.attr(ATTR_UDP_PENDING_WRITE_BYTES).setIfAbsent(newState);
        return oldState != null ? oldState : newState;
    }

    private static void releaseUdpPacket(DatagramPacket packet, String metricPrefix, String tags,
                                         String reason, int queuedBytes, int limitBytes) {
        Bytes.release(packet);
        String metricTags = appendUdpMetricTags(tags,
                "reason=" + reason + ",limitBucket=" + udpLimitBucket(limitBytes));
        recordUdpMetric(metricPrefix, "drop.count",
                metricTags);
        if (queuedBytes > 0) {
            recordUdpMetric(metricPrefix, "pending.write.bytes", metricTags, queuedBytes);
        }
    }

    private static void releaseUdpPacketByMtu(DatagramPacket packet, String metricPrefix, String tags,
                                              int bytes, int udpMtu) {
        Bytes.release(packet);
        String metricTags = appendUdpMetricTags(tags,
                "reason=mtu-exceeded,mtuBucket=" + udpMtuBucket(udpMtu));
        recordUdpMetric(metricPrefix, "drop.count", metricTags);
        recordUdpMetric(metricPrefix, "mtu.drop.count", metricTags);
        recordUdpMetric(metricPrefix, "mtu.drop.bytes", metricTags, bytes);
    }

    private static void recordUdpMetric(String metricPrefix, String suffix, String tags) {
        recordUdpMetric(metricPrefix, suffix, tags, 1D);
    }

    private static void recordUdpMetric(String metricPrefix, String suffix, String tags, double value) {
        if (metricPrefix == null) {
            return;
        }
        DiagnosticMetrics.record(metricPrefix + "." + suffix, value, tags);
    }

    private static String appendUdpMetricTags(String tags, String extra) {
        if (extra == null || extra.isEmpty()) {
            return tags;
        }
        if (tags == null || tags.isEmpty()) {
            return extra;
        }
        return tags + "," + extra;
    }

    public static String udpMetricTags(String component, String path, String flow, String result, String reason) {
        StringBuilder b = new StringBuilder(64);
        appendUdpMetricTag(b, "component", component);
        appendUdpMetricTag(b, "path", path);
        appendUdpMetricTag(b, "flow", flow);
        appendUdpMetricTag(b, "result", result);
        appendUdpMetricTag(b, "reason", reason);
        return b.toString();
    }

    private static void appendUdpMetricTag(StringBuilder b, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (b.length() > 0) {
            b.append(',');
        }
        b.append(name).append('=').append(value);
    }

    private static String udpLimitBucket(int limitBytes) {
        if (limitBytes <= 64 * 1024) {
            return "lte64k";
        }
        if (limitBytes <= 256 * 1024) {
            return "lte256k";
        }
        if (limitBytes <= 1024 * 1024) {
            return "lte1m";
        }
        return "gt1m";
    }

    private static String udpMtuBucket(int udpMtu) {
        if (udpMtu <= 1200) {
            return "lte1200";
        }
        if (udpMtu <= 1300) {
            return "lte1300";
        }
        if (udpMtu <= 1400) {
            return "lte1400";
        }
        if (udpMtu <= 1500) {
            return "lte1500";
        }
        return "gt1500";
    }

    /**
     * 优雅暂停 read（避免重复调用 setAutoRead）
     */
    public static void disableAutoRead(Channel ch) {
        ChannelConfig config = ch.config();
        if (config.isAutoRead()) {
            config.setAutoRead(false);
        }
    }

    /**
     * 优雅恢复 read
     */
    public static void enableAutoRead(Channel ch) {
        ChannelConfig config = ch.config();
        if (!config.isAutoRead()) {
            config.setAutoRead(true);
            // 如果之前有积压的 read，可以立即拉数据，减少一个 EventLoop 轮次延迟
            ch.read();
        }
    }

    // ctx.channel()会为null
    public static void closeOnFlushed(Channel channel) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        // ServerChannel 没有可刷出的出站缓冲，直接 close 避免对监听通道执行 writeAndFlush。
        if (channel instanceof ServerChannel) {
            channel.close();
            return;
        }
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    public static ByteBuf getMessageBuf(Object msg) {
        if (msg instanceof DatagramPacket) {
            return ((DatagramPacket) msg).content();
        } else if (msg instanceof ByteBuf) {
            return (ByteBuf) msg;
        } else {
            throw new InvalidException("Unsupported msg type: {}", msg.getClass());
        }
    }

    public static String protocolName(Channel channel) {
        return channel instanceof DatagramChannel ? "UDP" : "TCP";
    }
    // endregion

    // region Address
    public static boolean isBypass(Iterable<String> bypassList, String host) {
        if (bypassList == null) {
            return false;
        }
        for (String bypass : bypassList) {
            if (Files.wildcardMatch(host, bypass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * isSiteLocalAddress()IPv4: 10/8, 172.16/12, 192.168/16 IPv6: fc00::/7 (ULA)是（最常用私有地址）常规内网地址
     * isLoopbackAddress()127.0.0.0/8, ::1是（本机回环）通常也算作本地地址
     * isLinkLocalAddress()IPv4: 169.254.0.0/16 IPv6: fe80::/10是（链路本地）自动分配的临时地址，无路由
     *
     * @param ip
     * @return
     */
    public static boolean isPrivateIp(InetAddress ip) {
        return ip.isSiteLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress();
    }

    public static boolean isValidIp(String ip) {
        return NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip);
    }

    public static InetSocketAddress getRemoteAddress(Channel channel) {
        if (channel == null)
            return null;
        java.net.SocketAddress addr = channel.remoteAddress();
        if (addr instanceof InetSocketAddress) {
            return (InetSocketAddress) addr;
        }
        return new InetSocketAddress("127.0.0.1", 0);
    }

    public static InetSocketAddress getLocalAddress(Channel channel) {
        if (channel == null)
            return null;
        java.net.SocketAddress addr = channel.localAddress();
        if (addr instanceof InetSocketAddress) {
            return (InetSocketAddress) addr;
        }
        return new InetSocketAddress("127.0.0.1", 0);
    }

    public static void setOriginRemoteAddress(Channel channel, InetSocketAddress originRemoteAddress) {
        if (channel == null || originRemoteAddress == null) {
            return;
        }
        channel.attr(ATTR_ORIGIN_REMOTE_ADDR).set(originRemoteAddress);
    }

    public static InetSocketAddress getOriginRemoteAddress(Channel channel) {
        if (channel == null) {
            return null;
        }

        InetSocketAddress originRemoteAddress = getAttr(channel, ATTR_ORIGIN_REMOTE_ADDR, false);
        if (originRemoteAddress != null) {
            return originRemoteAddress;
        }

        originRemoteAddress = resolveOriginRemoteAddress(channel.remoteAddress(), EndpointTracer.TCP);
        if (originRemoteAddress == null) {
            originRemoteAddress = getRemoteAddress(channel);
        }
        setOriginRemoteAddress(channel, originRemoteAddress);
        return originRemoteAddress;
    }

    public static InetSocketAddress resolveOriginRemoteAddress(SocketAddress remoteAddress, EndpointTracer tracer) {
        if (remoteAddress == null || tracer == null) {
            return null;
        }
        InetSocketAddress originRemoteAddress = tracer.find(remoteAddress);
        if (originRemoteAddress != null) {
            return originRemoteAddress;
        }
        return remoteAddress instanceof InetSocketAddress ? (InetSocketAddress) remoteAddress : null;
    }

    public static String getLoopbackHostAddress() {
        if (loopbackAddr == null) {
            loopbackAddr = getLoopbackAddress().getHostAddress();
        }
        return loopbackAddr;
    }

    public static InetAddress getLoopbackAddress() {
        return InetAddress.getLoopbackAddress();
    }

    @SneakyThrows
    public static InetAddress getAnyLocalAddress() {
        return parseIpAddress("0.0.0.0");
    }

    public static InetAddress getLocalAddress() {
        return getLocalAddress(false);
    }

    @SneakyThrows
    public static InetAddress getLocalAddress(boolean supportsMulticast) {
        Inet4Address first = Linq.from(getLocalAddresses(supportsMulticast)).orderByDescending(Inet4Address::isSiteLocalAddress).firstOrDefault();
        if (first != null) {
            return first;
        }
        return InetAddress.getLocalHost(); // may return 127.0.0.1
    }

    @SneakyThrows
    public static List<Inet4Address> getLocalAddresses(boolean supportsMulticast) {
        List<Inet4Address> ips = new ArrayList<>();
        Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
        while (networks.hasMoreElements()) {
            NetworkInterface network = networks.nextElement();
            if (network.isLoopback() || network.isVirtual() || !network.isUp() || (supportsMulticast && !network.supportsMulticast())) {
                continue;
            }
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || !(addr instanceof Inet4Address)) {
                    continue;
                }
                // log.info("DUMP: {} - {} {} - {} {} - {} {} {}", addr.isMulticastAddress(),
                // addr.isSiteLocalAddress(), addr.isLinkLocalAddress(),
                // addr.isMCSiteLocal(), addr.isMCLinkLocal(),
                // addr.isMCGlobal(), addr.isMCOrgLocal(), addr.isMCNodeLocal());
                ips.add((Inet4Address) addr);
            }
        }
        if (supportsMulticast && ips.isEmpty()) {
            throw new InvalidException("Not multicast network found");
        }
        return ips;
    }

    public static InetSocketAddress newLoopbackEndpoint(int port) {
        return new InetSocketAddress(getLoopbackAddress(), port);
    }

    public static InetSocketAddress newAnyEndpoint(int port) {
        // return new InetSocketAddress(getAnyLocalAddress(), port);
        return new InetSocketAddress(port);
    }

    public static InetSocketAddress newEndpoint(String endpoint, int port) {
        return newEndpoint(parseEndpoint(endpoint), port);
    }

    public static InetSocketAddress newEndpoint(@NonNull InetSocketAddress endpoint, int port) {
        if (endpoint.getPort() == port) {
            return endpoint;
        }
        return newUnresolvedEndpoint(endpoint.getHostString(), port);
    }

    @SneakyThrows
    public static List<InetSocketAddress> newAllEndpoints(@NonNull InetSocketAddress endpoint) {
        InetAddress address = endpoint.getAddress();
        if (address != null) {
            return Collections.singletonList(new InetSocketAddress(address, endpoint.getPort()));
        }
        String host = endpoint.getHostString();
        return Collections.singletonList(newUnresolvedEndpoint(host, endpoint.getPort()));
    }

    public static InetSocketAddress parseEndpoint(@NonNull String endpoint) {
        String value = endpoint.trim();
        if (value.isEmpty()) {
            throw new InvalidException("Invalid endpoint {}", endpoint);
        }

        String host;
        String portText;
        if (value.charAt(0) == '[') {
            int close = value.indexOf(']');
            if (close <= 1 || close + 1 >= value.length() || value.charAt(close + 1) != ':') {
                throw new InvalidException("Invalid endpoint {}", endpoint);
            }
            host = value.substring(1, close);
            portText = value.substring(close + 2);
        } else {
            int i = value.lastIndexOf(":");
            if (i <= 0 || i == value.length() - 1) {
                throw new InvalidException("Invalid endpoint {}", endpoint);
            }

            host = value.substring(0, i);
            portText = value.substring(i + 1);
            if (host.indexOf(':') != -1 && !isValidIp(host)) {
                throw new InvalidException("Invalid endpoint {}", endpoint);
            }
        }

        host = host.trim();
        if (host.isEmpty()) {
            throw new InvalidException("Invalid endpoint {}", endpoint);
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new InvalidException("Invalid endpoint {}", endpoint);
        }
        if (port < 0 || port > 65535) {
            throw new InvalidException("Invalid endpoint {}", endpoint);
        }
        return newUnresolvedEndpoint(host, port);
    }

    /**
     * 字面量 IP 转为已解析地址；域名保持 unresolved，避免触发本机 DNS。
     */
    public static InetSocketAddress newUnresolvedEndpoint(@NonNull String host, int port) {
        String h = host.trim();
        return isValidIp(h) ? new InetSocketAddress(parseIpAddress(h), port)
                : InetSocketAddress.createUnresolved(h, port);
    }

    @SneakyThrows
    public static InetAddress parseIpAddress(String ip) {
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(ip);
        if (bytes == null) {
            throw new InvalidException("Invalid IP address {}", ip);
        }
        return InetAddress.getByAddress(bytes);
    }

    public static String toString(InetSocketAddress endpoint) {
        if (endpoint == null) {
            return "NULL";
        }
        return String.format("%s:%s", endpoint.getHostString(), endpoint.getPort());
    }

    public static String toString(SocketAddress endpoint) {
        if (endpoint instanceof InetSocketAddress) {
            return toString((InetSocketAddress) endpoint);
        }
        return endpoint == null ? "NULL" : endpoint.toString();
    }

    public static InetSocketAddress asInetAddress(SocketAddress endpoint) {
        return endpoint instanceof InetSocketAddress ? (InetSocketAddress) endpoint : null;
    }

    public static InetSocketAddress requireInetAddress(SocketAddress endpoint) {
        InetSocketAddress address = asInetAddress(endpoint);
        if (address == null) {
            throw new InvalidException("SocketAddress {} is not InetSocketAddress", endpoint);
        }
        return address;
    }

    public static void closeOnFlushed(Socket socket) {
        if (socket.isClosed()) {
            return;
        }

        quietly(() -> {
            if (socket.isConnected()) {
                socket.setSoLinger(true, 2);
                if (!socket.isOutputShutdown()) {
                    socket.shutdownOutput();
                }
                if (!socket.isInputShutdown()) {
                    socket.shutdownInput();
                }
            }
            socket.close();
        });
    }

    public static Linq<SocketInfo> socketInfos(SocketProtocol protocol) {
        try (ShellCommand cmd = new ShellCommand("netstat -aon")) {
            List<SocketInfo> list = new ArrayList<>();
            cmd.onPrintOut.add((s, e) -> {
                String line = e.getLine();
                if (!line.contains(protocol.name())) {
                    return;
                }

                String[] arr = Linq.from(Strings.split(line, "  ")).select(String::trim).where(p -> !p.isEmpty()).toArray();
                try {
                    SocketProtocol p = SocketProtocol.valueOf(arr[0]);
                    SocketInfo sockInfo;
                    if (p == SocketProtocol.TCP) {
                        sockInfo = new SocketInfo(p, parseEndpoint(arr[1]), parseEndpoint(arr[2]),
                                TcpStatus.valueOf(arr[3]), Long.parseLong(arr[4]));
                    } else {
                        sockInfo = new SocketInfo(p, parseEndpoint(arr[1]), null,
                                null, Long.parseLong(arr[3]));
                    }
                    list.add(sockInfo);
                } catch (Exception ex) {
                    log.error("Parse line {} fail", toJsonString(arr), ex);
                }
            });
            cmd.start().waitFor();

            Linq<SocketInfo> q = Linq.from(list, true);
            q.forEach(p -> p.setProcessName(processName(p.pid)));
            return q;
        }
    }

    static String processName(long pid) {
        return Cache.getOrSet(fastCacheKey("processName", pid), k -> {
            $<String> name = $();
            try (ShellCommand cmd = new ShellCommand(String.format("tasklist /fi \"pid eq %s\"", pid))) {
                String t = String.format(" %s", pid);
                cmd.onPrintOut.add((s, e) -> {
                    int i = e.getLine().indexOf(t);
                    if (i == -1) {
                        return;
                    }
                    name.v = e.getLine().substring(0, i).trim();
                });
                cmd.start().waitFor();
            }
            return name.v;
        });
    }
    // #endregion

    // region httpProxy
    public static <T> T httpProxyInvoke(String proxyAddr, BiFunc<String, T> func) {
        setHttpProxy(proxyAddr);
        try {
            return func.apply(proxyAddr);
        } finally {
            clearHttpProxy();
        }
    }

    public static void setHttpProxy(String proxyAddr) {
        setHttpProxy(proxyAddr, null, null, null);
    }

    public static void setHttpProxy(String proxyAddr, List<String> nonProxyHosts, String userName, String password) {
        InetSocketAddress ipe = parseEndpoint(proxyAddr);
        Properties prop = System.getProperties();
        String proxyHost = ipe.getHostString();
        prop.setProperty("http.proxyHost", proxyHost);
        prop.setProperty("http.proxyPort", String.valueOf(ipe.getPort()));
        prop.setProperty("https.proxyHost", proxyHost);
        prop.setProperty("https.proxyPort", String.valueOf(ipe.getPort()));
        if (!CollectionUtils.isEmpty(nonProxyHosts)) {
            // "localhost|192.168.1.*"
            prop.setProperty("http.nonProxyHosts", String.join("|", nonProxyHosts));
        }
        if (userName != null && password != null) {
            Authenticator.setDefault(new UserAuthenticator(userName, password));
        }
    }

    public static void clearHttpProxy() {
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("http.nonProxyHosts");
    }

    @RequiredArgsConstructor
    static class UserAuthenticator extends Authenticator {
        final String userName;
        final String password;

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(userName, password.toCharArray());
        }
    }
    // endregion
}
