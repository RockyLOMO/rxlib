package org.rx.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.NetUtil;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.$;
import org.rx.bean.FlagsEnum;
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
        String DNS = "DNS";
        String SS = "SS";
    }

    public static final String ZIP_ENCODER = "ZIP_ENCODER";
    public static final String ZIP_DECODER = "ZIP_DECODER";
    public static final LengthFieldPrepender INT_LENGTH_PREPENDER = new LengthFieldPrepender(4);
    static final String M_0 = "lookupAllHostAddr";
    static final LoggingHandler DEFAULT_LOG = new LoggingHandler(LogLevel.INFO);
    static final Map<String, MultithreadEventLoopGroup> reactors = new ConcurrentHashMap<>();
    static String loopbackAddr;
    static volatile DnsServer.ResolveInterceptor nsInterceptor;

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
        return reactors.computeIfAbsent(reactorName, k -> {
            int amount = RxConfig.INSTANCE.getNet().getReactorThreadAmount();
            return isTcp && Epoll.isAvailable() ? new EpollEventLoopGroup(amount) : new NioEventLoopGroup(amount);
        });
    }

    //Don't use executor
    private static EventLoopGroup newEventLoop(int threadAmount) {
        return Epoll.isAvailable() ? new EpollEventLoopGroup(threadAmount) : new NioEventLoopGroup(threadAmount);
    }

    public static Class<? extends SocketChannel> channelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    //region tcp
    public static ServerBootstrap serverBootstrap(BiAction<SocketChannel> initChannel) {
        return serverBootstrap(null, initChannel);
    }

    public static ServerBootstrap serverBootstrap(SocketConfig config, BiAction<SocketChannel> initChannel) {
        if (config == null) {
            config = new SocketConfig();
        }
        MemoryMode mode = config.getMemoryMode();
        int connectTimeoutMillis = config.getConnectTimeoutMillis();
        boolean highNetwork = connectTimeoutMillis <= SocketConfig.DELAY_TIMEOUT_MILLIS;

        int bossThreadAmount = 1; //Equal to the number of bind(), default 1
        AdaptiveRecvByteBufAllocator recvByteBufAllocator = mode.adaptiveRecvByteBufAllocator(false);
        WriteBufferWaterMark writeBufferWaterMark = mode.writeBufferWaterMark();
        ServerBootstrap b = new ServerBootstrap()
                .group(newEventLoop(bossThreadAmount), config.isUseSharedTcpEventLoop() ? reactor(ReactorNames.SHARED_TCP, true) : newEventLoop(0))
                .channel(Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, mode.getBacklog())
//                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .childOption(ChannelOption.TCP_NODELAY, highNetwork)
//                .childOption(ChannelOption.SO_KEEPALIVE, !highNetwork)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .childHandler(WaterMarkHandler.DEFAULT);
        if (config.isEnableLog()) {
            //netty log
            b.handler(DEFAULT_LOG);
        }
        if (initChannel != null) {
            b.childHandler(new ChannelInitializer<SocketChannel>() {
                @SneakyThrows
                @Override
                protected void initChannel(SocketChannel socketChannel) {
                    initChannel.invoke(socketChannel);
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
        MemoryMode mode = config.getMemoryMode();
        int connectTimeoutMillis = config.getConnectTimeoutMillis();
        boolean highNetwork = connectTimeoutMillis <= SocketConfig.DELAY_TIMEOUT_MILLIS;

        AdaptiveRecvByteBufAllocator recvByteBufAllocator = mode.adaptiveRecvByteBufAllocator(false);
        WriteBufferWaterMark writeBufferWaterMark = mode.writeBufferWaterMark();
        Bootstrap b = new Bootstrap()
                .group(eventLoopGroup)
                .channel(channelClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .option(ChannelOption.TCP_NODELAY, highNetwork)
//                .option(ChannelOption.SO_KEEPALIVE, !highNetwork)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark)
                .handler(WaterMarkHandler.DEFAULT);
        if (config.isEnableLog()) {
            b.handler(DEFAULT_LOG);
        }
        if (initChannel != null) {
            b.handler(new ChannelInitializer<SocketChannel>() {
                @SneakyThrows
                @Override
                protected void initChannel(SocketChannel socketChannel) {
                    initChannel.invoke(socketChannel);
                }
            });
        }
        return b;
    }

    @SneakyThrows
    public static void addFrontendHandler(Channel channel, SocketConfig config) {
        FlagsEnum<TransportFlags> flags = config.getTransportFlags();
        if (flags == null) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (flags.has(TransportFlags.FRONTEND_SSL)) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
            pipeline.addLast(sslCtx.newHandler(channel.alloc()));
        }
        if (flags.has(TransportFlags.FRONTEND_AES)) {
            if (config.getAesKey() == null) {
                throw new InvalidException("AES key is empty");
            }
            pipeline.addLast(new AESCodec(config.getAesKey()).channelHandlers());
        }
        if (flags.has(TransportFlags.FRONTEND_COMPRESS)) {
            pipeline.addLast(ZIP_ENCODER, ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP))
                    .addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
        }
    }

    @SneakyThrows
    public static void addBackendHandler(Channel channel, SocketConfig config, InetSocketAddress remoteEndpoint) {
        FlagsEnum<TransportFlags> flags = config.getTransportFlags();
        if (flags == null) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (flags.has(TransportFlags.BACKEND_SSL)) {
            SslContext sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            pipeline.addLast(sslCtx.newHandler(channel.alloc(), remoteEndpoint.getHostString(), remoteEndpoint.getPort()));
        }
        if (flags.has(TransportFlags.BACKEND_AES)) {
            if (config.getAesKey() == null) {
                throw new InvalidException("AES key is empty");
            }
            pipeline.addLast(new AESCodec(config.getAesKey()).channelHandlers());
        }
        if (flags.has(TransportFlags.BACKEND_COMPRESS)) {
            pipeline.addLast(ZIP_ENCODER, ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP))
                    .addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
        }
    }
    //endregion

    public static Bootstrap udpBootstrap(MemoryMode mode, BiAction<NioDatagramChannel> initChannel) {
        return udpBootstrap(ReactorNames.SHARED_UDP, mode, false, initChannel);
    }

    public static Bootstrap udpBootstrap(String reactorName, MemoryMode mode, BiAction<NioDatagramChannel> initChannel) {
        return udpBootstrap(reactorName, mode, false, initChannel);
    }

    public static Bootstrap udpBootstrap(MemoryMode mode, boolean multicast, BiAction<NioDatagramChannel> initChannel) {
        return udpBootstrap(ReactorNames.SHARED_UDP, mode, multicast, initChannel);
    }

    //BlockingOperationException 因为执行sync()-wait和notify的是同一个EventLoop中的线程
    //DefaultDatagramChannelConfig
    @SneakyThrows
    static Bootstrap udpBootstrap(@NonNull String reactorName, MemoryMode mode, boolean multicast, BiAction<NioDatagramChannel> initChannel) {
        if (mode == null) {
            mode = MemoryMode.LOW;
        }
        NetworkInterface mif = null;
        if (multicast) {
//            MulticastSocket ms = new MulticastSocket();
//            mif = ms.getNetworkInterface();
            mif = NetworkInterface.getByInetAddress(Sockets.getLocalAddress(true));
        }

        Bootstrap b = new Bootstrap().group(reactor(reactorName, false))
//                .option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.RCVBUF_ALLOCATOR, mode.adaptiveRecvByteBufAllocator(true))
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, mode.writeBufferWaterMark())
                .handler(WaterMarkHandler.DEFAULT);
        if (multicast) {
            b.channelFactory(() -> new NioDatagramChannel(InternetProtocolFamily.IPv4))
                    .option(ChannelOption.IP_MULTICAST_IF, mif)
                    .option(ChannelOption.SO_REUSEADDR, true);
        } else {
            b.channel(NioDatagramChannel.class);
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
            String pn = protocolName(f.channel());
            if (!f.isSuccess()) {
                log.error("{} Listen on port {} fail", pn, port, f.cause());
                return;
            }
            log.info("{} Listened on {}", pn, f.channel().localAddress());
        };
    }

    public static ChannelFutureListener logConnect(InetSocketAddress endpoint) {
        return f -> {
            if (!f.isSuccess()) {
                log.error("TCP Connect {} fail", endpoint, f.cause());
                return;
            }
            log.info("TCP Connected {}", endpoint);
        };
    }

    public static void dumpPipeline(String name, Channel channel) {
        if (log.isTraceEnabled()) {
            ChannelPipeline pipeline = channel.pipeline();
            List<ChannelInboundHandler> inboundHandlers = new LinkedList<>();
            List<ChannelOutboundHandler> outboundHandlers = new LinkedList<>();
            log.trace(name + " list:");
            for (Map.Entry<String, ChannelHandler> entry : pipeline) {
                String prefix;
                ChannelHandler handler = entry.getValue();
                if (handler instanceof ChannelInboundHandler) {
                    prefix = "in";
                    inboundHandlers.add((ChannelInboundHandler) handler);
                } else if (handler instanceof ChannelOutboundHandler) {
                    prefix = "out";
                    outboundHandlers.add((ChannelOutboundHandler) handler);
                } else {
                    prefix = "?";
                    log.trace(String.format("%s %s: %s", prefix, entry.getKey(), entry.getValue()));
                }
            }
            log.trace(name + " sorted:");
            for (ChannelInboundHandler handler : inboundHandlers) {
                log.trace(String.format("in %s", handler));
            }
            for (ChannelOutboundHandler handler : outboundHandlers) {
                log.trace(String.format("out %s", handler));
            }
        }
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

    @SneakyThrows
    public static boolean isLanIp(InetAddress ip) {
        String hostAddress = ip.getHostAddress();
        if (Strings.hashEquals(getLoopbackHostAddress(), hostAddress)) {
            return true;
        }
        return isBypass(RxConfig.INSTANCE.getNet().getLanIps(), hostAddress);
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
        try (ShellCommander cmd = new ShellCommander("netstat -aon")) {
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
            try (ShellCommander cmd = new ShellCommander(String.format("tasklist /fi \"pid eq %s\"", pid))) {
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
