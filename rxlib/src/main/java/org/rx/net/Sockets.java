package org.rx.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
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
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.$;
import org.rx.bean.FlagsEnum;
import org.rx.core.StringBuilder;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.io.Files;
import org.rx.net.dns.DnsClient;
import org.rx.net.dns.DnsServer;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.bean.$.$;
import static org.rx.core.Extends.quietly;
import static org.rx.core.Sys.fastCacheKey;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public final class Sockets {
    public interface ReactorNames {
        String SHARED_TCP = "_TCP";
        String SHARED_UDP = "_UDP";
        String RPC = "RPC";
    }

    public static final String ZIP_ENCODER = "ZIP_ENCODER";
    public static final String ZIP_DECODER = "ZIP_DECODER";
    public static final LengthFieldPrepender INT_LENGTH_FIELD_ENCODER = new LengthFieldPrepender(4);
    static final String M_0 = "lookupAllHostAddr";
    static final LoggingHandler DEFAULT_LOG = new LoggingHandler(LogLevel.INFO);
    static final Map<String, MultithreadEventLoopGroup> reactors = new ConcurrentHashMap<>();
    static String loopbackAddr;
    static volatile DnsServer.ResolveInterceptor nsInterceptor;

    public static LengthFieldBasedFrameDecoder intLengthFieldDecoder() {
        return new LengthFieldBasedFrameDecoder(Constants.MAX_HEAP_BUF_SIZE, 0, 4, 0, 4);
    }

    //region netty
    public static void injectNameService(List<InetSocketAddress> nameServerList) {
        if (CollectionUtils.isEmpty(nameServerList)) {
            throw new InvalidException("Empty server list");
        }
        DnsClient client = new DnsClient(nameServerList);
        injectNameService(client::resolveAll);
    }

    @SneakyThrows
    public static void injectNameService(@NonNull DnsServer.ResolveInterceptor interceptor) {
        if (nsInterceptor == null) {
            synchronized (Sockets.class) {
                if (nsInterceptor == null) {
                    nsInterceptor = interceptor;
                    Class<?> type = InetAddress.class;
                    try {
                        Field field = type.getDeclaredField("nameService");
                        Reflects.setAccess(field);
                        field.set(null, nsProxy(field.get(null)));
                    } catch (NoSuchFieldException e) {
                        Field field = type.getDeclaredField("nameServices");
                        Reflects.setAccess(field);
                        List<Object> nsList = (List<Object>) field.get(null);
                        nsList.set(0, nsProxy(nsList.get(0)));
                    }
                }
            }
        }
        nsInterceptor = interceptor;
    }

    private static Object nsProxy(Object ns) {
        Class<?> type = ns.getClass();
        InetAddress[] empty = new InetAddress[0];
        return Proxy.newProxyInstance(type.getClassLoader(), type.getInterfaces(), (pObject, method, args) -> {
            if (Strings.hashEquals(method.getName(), M_0)) {
                String host = (String) args[0];
                //If all interceptors can't handle it, the source object will process it.
                try {
                    List<InetAddress> addresses = nsInterceptor.resolveHost(host);
                    if (!CollectionUtils.isEmpty(addresses)) {
                        return addresses.toArray(empty);
                    }
                } catch (Exception e) {
                    log.info("nsProxy {}", e.getMessage());
                }
            }
            return Reflects.invokeMethod(method, ns, args);
        });
    }

    public static EventLoopGroup reactor(String reactorName, boolean isTcp) {
//        return reactors.computeIfAbsent(reactorName, k -> {
//            int amount = RxConfig.INSTANCE.getNet().getReactorThreadAmount();
//            return isTcp && Epoll.isAvailable() ? new EpollEventLoopGroup(amount) : new NioEventLoopGroup(amount);
//        });
        return reactors.computeIfAbsent(reactorName, k -> {
            int amount = RxConfig.INSTANCE.getNet().getReactorThreadAmount();
            return Epoll.isAvailable() ? new EpollEventLoopGroup(amount) : new NioEventLoopGroup(amount);
        });
    }

    //Don't use executor
    private static EventLoopGroup newEventLoop(int threadAmount) {
        return Epoll.isAvailable() ? new EpollEventLoopGroup(threadAmount) : new NioEventLoopGroup(threadAmount);
    }

    public static Class<? extends SocketChannel> tcpChannelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    public static Class<? extends DatagramChannel> udpChannelClass() {
        return Epoll.isAvailable() ? EpollDatagramChannel.class : NioDatagramChannel.class;
    }

    //region tcp
    public static ServerBootstrap serverBootstrap(BiAction<SocketChannel> initChannel) {
        return serverBootstrap(null, initChannel);
    }

    public static ServerBootstrap serverBootstrap(SocketConfig config, BiAction<SocketChannel> initChannel) {
        if (config == null) {
            config = new SocketConfig();
        }
        if (config.getOptimalSettings() == null) {
            config.setOptimalSettings(OptimalSettings.EMPTY);
        }

        OptimalSettings op = config.getOptimalSettings();
        op.calculate();
        int backlog = op.backlog;
        WriteBufferWaterMark writeBufferWaterMark = op.writeBufferWaterMark;
        AdaptiveRecvByteBufAllocator recvByteBufAllocator = op.recvByteBufAllocator;
        int connectTimeoutMillis = config.getConnectTimeoutMillis();
        final int bossThreadAmount = 1; //Equal to the number of bind(), default 1
        ServerBootstrap b = new ServerBootstrap()
                .group(newEventLoop(bossThreadAmount), config.isUseSharedTcpEventLoop() ? reactor(ReactorNames.SHARED_TCP, true) : newEventLoop(0))
                .channel(Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, backlog)
//                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .childHandler(new BackpressureHandler(true));
        if (config.isDebug()) {
            //netty log
            b.handler(DEFAULT_LOG);
        }
        if (initChannel != null) {
            b.childHandler(new ChannelInitializer<SocketChannel>() {
                @SneakyThrows
                @Override
                protected void initChannel(SocketChannel socketChannel) {
                    socketChannel.pipeline().addLast(GlobalChannelHandler.DEFAULT);
                    initChannel.invoke(socketChannel);
//                    socketChannel.pipeline().addLast(ChannelExceptionHandler.DEFAULT);
                }
            });
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
        return bootstrap(ReactorNames.SHARED_TCP, config, initChannel);
    }

    public static Bootstrap bootstrap(String reactorName, SocketConfig config, BiAction<SocketChannel> initChannel) {
        return bootstrap(reactor(reactorName, true), config, initChannel);
    }

    public static Bootstrap bootstrap(@NonNull EventLoopGroup eventLoopGroup, SocketConfig config, BiAction<SocketChannel> initChannel) {
        if (config == null) {
            config = new SocketConfig();
        }
        if (config.getOptimalSettings() == null) {
            config.setOptimalSettings(OptimalSettings.EMPTY);
        }

        OptimalSettings op = config.getOptimalSettings();
        op.calculate();
        WriteBufferWaterMark writeBufferWaterMark = op.writeBufferWaterMark;
        AdaptiveRecvByteBufAllocator recvByteBufAllocator = op.recvByteBufAllocator;
        int connectTimeoutMillis = config.getConnectTimeoutMillis();
        Bootstrap b = new Bootstrap()
                .group(eventLoopGroup)
                .channel(tcpChannelClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .handler(new BackpressureHandler(true));
        if (config.isDebug()) {
            b.handler(DEFAULT_LOG);
        }
        if (initChannel != null) {
            b.handler(new ChannelInitializer<SocketChannel>() {
                @SneakyThrows
                @Override
                protected void initChannel(SocketChannel socketChannel) {
                    socketChannel.pipeline().addLast(GlobalChannelHandler.DEFAULT);
                    initChannel.invoke(socketChannel);
//                    socketChannel.pipeline().addLast(ChannelExceptionHandler.DEFAULT);
                }
            });
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
            pipeline.addBefore(baseName, handler.getClass().getSimpleName(), handler);
        }
    }

    @SneakyThrows
    public static Channel addServerHandler(Channel channel, SocketConfig config) {
        FlagsEnum<TransportFlags> flags = config.getTransportFlags();
        if (flags == null) {
            return channel;
        }

        //入站事件（如数据读取、连接建立等）由 ChannelInboundHandler 处理，传播方向是从 pipeline 的 head 到 tail。
        //出站事件（如数据写入、连接关闭等）由 ChannelOutboundHandler 处理，传播方向是从 pipeline 的 tail 到 head。
        ChannelPipeline pipeline = channel.pipeline();
        if (flags.has(TransportFlags.SERVER_TLS)) {
            pipeline.addLast(getSelfSignedTls().newHandler(channel.alloc()));
        }

        if (flags.has(TransportFlags.SERVER_HTTP_PSEUDO_READ)) {
            pipeline.addLast(new HttpPseudoHeaderDecoder());
        }
        if (flags.has(TransportFlags.SERVER_HTTP_PSEUDO_WRITE)) {
            pipeline.addLast(HttpPseudoHeaderEncoder.DEFAULT);
        }

        //先压缩再加密
        //支持LengthField?
        boolean g = flags.has(TransportFlags.GFW);
        if (!g) {
            if (flags.has(TransportFlags.SERVER_COMPRESS_READ)) {
                pipeline.addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            if (flags.has(TransportFlags.SERVER_COMPRESS_WRITE)) {
                pipeline.addLast(ZIP_ENCODER, ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
            }
        }

        boolean hasCipherR = flags.has(TransportFlags.SERVER_CIPHER_READ),
                hasCipherW = flags.has(TransportFlags.SERVER_CIPHER_WRITE);
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

        if (g) {
            if (flags.has(TransportFlags.SERVER_COMPRESS_READ)) {
                pipeline.addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            if (flags.has(TransportFlags.SERVER_COMPRESS_WRITE)) {
                pipeline.addLast(ZIP_ENCODER, ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
            }
        }
//        dumpPipeline("server", channel);
        return channel;
    }

    public static Channel addClientHandler(Channel channel, SocketConfig config) {
        return addClientHandler(channel, config, null);
    }

    @SneakyThrows
    public static Channel addClientHandler(Channel channel, SocketConfig config, InetSocketAddress SNISpoofing) {
        FlagsEnum<TransportFlags> flags = config.getTransportFlags();
        if (flags == null) {
            return channel;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (flags.has(TransportFlags.CLIENT_TLS)) {
            SslContext tls = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            if (SNISpoofing == null) {
                SNISpoofing = Sockets.parseEndpoint("qq.com:443");
            }
            pipeline.addLast(tls.newHandler(channel.alloc(), SNISpoofing.getHostString(), SNISpoofing.getPort()));
        }

        if (flags.has(TransportFlags.CLIENT_HTTP_PSEUDO_READ)) {
            pipeline.addLast(new HttpPseudoHeaderDecoder());
        }
        if (flags.has(TransportFlags.CLIENT_HTTP_PSEUDO_WRITE)) {
            pipeline.addLast(HttpPseudoHeaderEncoder.DEFAULT);
        }

        boolean g = flags.has(TransportFlags.GFW);
        if (!g) {
            if (flags.has(TransportFlags.CLIENT_COMPRESS_READ)) {
                pipeline.addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            if (flags.has(TransportFlags.CLIENT_COMPRESS_WRITE)) {
                pipeline.addLast(ZIP_ENCODER, ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
            }
        }

        boolean hasCipherR = flags.has(TransportFlags.CLIENT_CIPHER_READ),
                hasCipherW = flags.has(TransportFlags.CLIENT_CIPHER_WRITE);
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

        if (g) {
            if (flags.has(TransportFlags.CLIENT_COMPRESS_READ)) {
                pipeline.addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            if (flags.has(TransportFlags.CLIENT_COMPRESS_WRITE)) {
                pipeline.addLast(ZIP_ENCODER, ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP));
            }
        }
//        dumpPipeline("client", channel);
        return channel;
    }

    @SneakyThrows
    public static SslContext getSelfSignedTls() {
        SelfSignedCertificate ssc = new SelfSignedCertificate("qq.com");
        return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
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
//                    in.appendMessageFormat(" {}[{}]", handlerName, handler);
                }
                if (handler instanceof ChannelOutboundHandler) {
                    out.append(handlerName).append(", ");
//                    out.appendMessageFormat(" {}[{}]", handlerName, handler);
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
    //endregion

    public static Bootstrap udpBootstrap(OptimalSettings op, BiAction<NioDatagramChannel> initChannel) {
        return udpBootstrap(ReactorNames.SHARED_UDP, op, false, initChannel);
    }

    public static Bootstrap udpBootstrap(String reactorName, OptimalSettings op, BiAction<NioDatagramChannel> initChannel) {
        return udpBootstrap(reactorName, op, false, initChannel);
    }

    public static Bootstrap udpBootstrap(OptimalSettings op, boolean multicast, BiAction<NioDatagramChannel> initChannel) {
        return udpBootstrap(ReactorNames.SHARED_UDP, op, multicast, initChannel);
    }

    //BlockingOperationException 因为执行sync()-wait和notify的是同一个EventLoop中的线程
    //DefaultDatagramChannelConfig
    @SneakyThrows
    static Bootstrap udpBootstrap(@NonNull String reactorName, OptimalSettings op, boolean multicast, BiAction<NioDatagramChannel> initChannel) {
        if (op == null) {
            op = OptimalSettings.EMPTY;
        }

        op.calculate();
        AdaptiveRecvByteBufAllocator recvByteBufAllocator = op.recvByteBufAllocator;
        NetworkInterface mif = null;
        if (multicast) {
//            MulticastSocket ms = new MulticastSocket();
//            mif = ms.getNetworkInterface();
            mif = NetworkInterface.getByInetAddress(Sockets.getLocalAddress(true));
        }

        //writeBufferWaterMark UDP无效
        Bootstrap b = new Bootstrap()
                .group(reactor(reactorName, false))
//                .option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator);
        if (multicast) {
            b.channelFactory(() -> Epoll.isAvailable()
                            ? new EpollDatagramChannel(InternetProtocolFamily.IPv4)
                            : new NioDatagramChannel(InternetProtocolFamily.IPv4))
                    .option(ChannelOption.IP_MULTICAST_IF, mif)
                    .option(ChannelOption.SO_REUSEADDR, true);
        } else {
            b.channel(Sockets.udpChannelClass());
        }
        b.handler(DEFAULT_LOG);
        if (initChannel != null) {
            b.handler(new ChannelInitializer<NioDatagramChannel>() {
                @SneakyThrows
                @Override
                protected void initChannel(NioDatagramChannel socketChannel) {
                    initChannel.invoke(socketChannel);
                }
            });
        }
        return b;
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
            // 可选：如果之前有积压的 read，可以主动触发一次
            ch.read();  // 立即拉数据，减少一个 EventLoop 轮次延迟
        }
    }

    //ctx.channel()会为null
    public static void closeOnFlushed(Channel channel) {
        if (channel == null || !channel.isActive()) {
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
        return channel instanceof NioDatagramChannel ? "UDP" : "TCP";
    }

    public static ChannelFutureListener logBind(int port) {
        return f -> {
            int realPort;
            Channel ch = f.channel();
            InetSocketAddress locAddr;
            if ((locAddr = (InetSocketAddress) ch.localAddress()) != null) {
                realPort = locAddr.getPort();
            } else {
                realPort = port;
            }
            String pn = Sockets.protocolName(ch);
            if (!f.isSuccess()) {
                log.error("Server[{}] {} listen on {} fail", ch.id(), pn, realPort, f.cause());
                return;
            }
            log.info("Server[{}] {} listened on {}", ch.id(), pn, realPort);
        };
    }
    //endregion

    //region Address
    public static boolean isBypass(Iterable<String> bypassList, String host) {
        if (bypassList == null) {
            return false;
        }
        for (String regex : bypassList) {
            if (Files.wildcardMatch(host, regex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 10.0.0.0/8
     * 172.16.0.0/12
     * 192.168.0.0/16
     *
     * @param ip
     * @return
     */
    public static boolean isPrivateIp(InetAddress ip) {
        byte[] ipBytes = ip.getAddress();
        // 将字节数组转换为无符号整数，用于比较
        int first = ipBytes[0] & 0xFF;  // 第一段
        int second = ipBytes[1] & 0xFF; // 第二段

        // 检查是否在 10.0.0.0 - 10.255.255.255 范围内
        if (first == 10) {
            return true;
        }
        // 检查是否在 172.16.0.0 - 172.31.255.255 范围内
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        // 检查是否在 192.168.0.0 - 192.168.255.255 范围内
        return first == 192 && second == 168;
    }

    public static boolean isValidIp(String ip) {
        return NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip);
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
        return InetAddress.getByName("0.0.0.0");
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
        return InetAddress.getLocalHost(); //may return 127.0.0.1
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
//                        || !addr.isSiteLocalAddress() //k8s会获取不到
                        || !(addr instanceof Inet4Address)) {
                    continue;
                }
//                log.info("DUMP: {} - {} {} - {} {} - {} {} {}", addr.isMulticastAddress(),
//                        addr.isSiteLocalAddress(), addr.isLinkLocalAddress(),
//                        addr.isMCSiteLocal(), addr.isMCLinkLocal(),
//                        addr.isMCGlobal(), addr.isMCOrgLocal(), addr.isMCNodeLocal());
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
        return new InetSocketAddress(getAnyLocalAddress(), port);
    }

    public static InetSocketAddress newEndpoint(String endpoint, int port) {
        return newEndpoint(parseEndpoint(endpoint), port);
    }

    public static InetSocketAddress newEndpoint(@NonNull InetSocketAddress endpoint, int port) {
        if (endpoint.getPort() == port) {
            return endpoint;
        }
        return new InetSocketAddress(endpoint.getAddress(), port);
    }

    @SneakyThrows
    public static List<InetSocketAddress> newAllEndpoints(@NonNull InetSocketAddress endpoint) {
        return Linq.from(InetAddress.getAllByName(endpoint.getHostString())).select(p -> new InetSocketAddress(p, endpoint.getPort())).toList();
    }

    public static InetSocketAddress parseEndpoint(@NonNull String endpoint) {
        int i = endpoint.lastIndexOf(":");
        if (i == -1) {
            throw new InvalidException("Invalid endpoint {}", endpoint);
        }

        String ip = endpoint.substring(0, i);
        int port = Integer.parseInt(endpoint.substring(i + 1));
        return new InetSocketAddress(ip, port);
//        return InetSocketAddress.createUnresolved(ip, port);  //DNS issues
    }

    public static String toString(InetSocketAddress endpoint) {
        if (endpoint == null) {
            return "NULL";
        }
        return String.format("%s:%s", endpoint.getHostString(), endpoint.getPort());
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
            cmd.onPrintOut.combine((s, e) -> {
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
                cmd.onPrintOut.combine((s, e) -> {
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
    //#endregion

    //region httpProxy
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
        prop.setProperty("http.proxyHost", ipe.getAddress().getHostAddress());
        prop.setProperty("http.proxyPort", String.valueOf(ipe.getPort()));
        prop.setProperty("https.proxyHost", ipe.getAddress().getHostAddress());
        prop.setProperty("https.proxyPort", String.valueOf(ipe.getPort()));
        if (!CollectionUtils.isEmpty(nonProxyHosts)) {
            //"localhost|192.168.1.*"
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
    //endregion
}
