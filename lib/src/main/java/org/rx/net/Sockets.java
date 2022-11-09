package org.rx.net;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.rx.bean.$;
import org.rx.core.*;
import org.rx.exception.InvalidException;
import org.rx.net.dns.DnsClient;
import org.rx.util.function.BiAction;
import org.rx.util.function.BiFunc;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static org.rx.bean.$.$;
import static org.rx.core.Sys.*;
import static org.rx.core.Extends.eq;
import static org.rx.core.Extends.quietly;

@Slf4j
public final class Sockets {
    public static final LengthFieldPrepender INT_LENGTH_PREPENDER = new LengthFieldPrepender(4);
    static final String M_0 = "lookupAllHostAddr";
    static final LoggingHandler DEFAULT_LOG = new LoggingHandler(LogLevel.INFO);
    static final String SHARED_TCP_REACTOR = "_TCP", SHARED_UDP_REACTOR = "_UDP", SHARED_UDP_SVR_REACTOR = "_UDP:SVR";
    static final Map<String, MultithreadEventLoopGroup> reactors = new ConcurrentHashMap<>();
    static String loopbackAddr;
    static volatile BiFunc<String, List<InetAddress>> nsInterceptor;

    public static void injectNameService(List<InetSocketAddress> nameServerList) {
        DnsClient client = CollectionUtils.isEmpty(nameServerList) ? DnsClient.inlandClient() : new DnsClient(nameServerList);
        injectNameService(client::resolveAll);
    }

    @SneakyThrows
    public static void injectNameService(@NonNull BiFunc<String, List<InetAddress>> fn) {
        if (nsInterceptor == null) {
            synchronized (Sockets.class) {
                if (nsInterceptor == null) {
                    nsInterceptor = fn;
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
        nsInterceptor = fn;
    }

    private static Object nsProxy(Object ns) {
        Class<?> type = ns.getClass();
        InetAddress[] empty = new InetAddress[0];
        return Proxy.newProxyInstance(type.getClassLoader(), type.getInterfaces(), (pObject, method, args) -> {
            if (Strings.hashEquals(method.getName(), M_0)) {
                String host = (String) args[0];
                //处理不了会交给源对象处理
                try {
                    List<InetAddress> addresses = nsInterceptor.invoke(host);
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

    static EventLoopGroup reactor(@NonNull String reactorName) {
        return reactors.computeIfAbsent(reactorName, k -> {
            int amount = RxConfig.INSTANCE.getNet().getReactorThreadAmount();
            return Epoll.isAvailable() ? new EpollEventLoopGroup(amount) : new NioEventLoopGroup(amount);
        });
    }

    public static EventLoopGroup udpReactor() {
        return udpReactor(SHARED_UDP_REACTOR);
    }

    static EventLoopGroup udpReactor(@NonNull String reactorName) {
        return reactors.computeIfAbsent(reactorName, k -> new NioEventLoopGroup(RxConfig.INSTANCE.getNet().getReactorThreadAmount()));
    }

    // not executor
    private static EventLoopGroup newEventLoop(int threadAmount) {
        return Epoll.isAvailable() ? new EpollEventLoopGroup(threadAmount) : new NioEventLoopGroup(threadAmount);
    }

    public static Class<? extends SocketChannel> channelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    private static Class<? extends ServerSocketChannel> serverChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

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

        int bossThreadAmount = 1; //等于bind的次数，默认1
        AdaptiveRecvByteBufAllocator recvByteBufAllocator = mode.adaptiveRecvByteBufAllocator(false);
        WriteBufferWaterMark writeBufferWaterMark = mode.writeBufferWaterMark();
        ServerBootstrap b = new ServerBootstrap()
                .group(newEventLoop(bossThreadAmount), config.isUseSharedTcpEventLoop() ? reactor(SHARED_TCP_REACTOR) : newEventLoop(0))
                .channel(serverChannelClass())
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
            //netty日志
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

    public static Bootstrap bootstrap(BiAction<SocketChannel> initChannel) {
        return bootstrap(SHARED_TCP_REACTOR, null, initChannel);
    }

    public static Bootstrap bootstrap(@NonNull String reactorName, SocketConfig config, BiAction<SocketChannel> initChannel) {
        return bootstrap(reactor(reactorName), config, initChannel);
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

    public static Bootstrap udpServerBootstrap(MemoryMode mode, BiAction<NioDatagramChannel> initChannel) {
        return udpBootstrap(SHARED_UDP_SVR_REACTOR, mode, initChannel);
    }

    public static Bootstrap udpBootstrap(MemoryMode mode, BiAction<NioDatagramChannel> initChannel) {
        return udpBootstrap(SHARED_UDP_REACTOR, mode, initChannel);
    }

    //BlockingOperationException 因为执行sync()-wait和notify的是同一个EventLoop中的线程
    //DefaultDatagramChannelConfig
    static Bootstrap udpBootstrap(String reactorName, MemoryMode mode, BiAction<NioDatagramChannel> initChannel) {
        if (mode == null) {
            mode = MemoryMode.LOW;
        }

        Bootstrap b = new Bootstrap().group(udpReactor(reactorName)).channel(NioDatagramChannel.class)
//                .option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.RCVBUF_ALLOCATOR, mode.adaptiveRecvByteBufAllocator(true))
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, mode.writeBufferWaterMark())
                .handler(WaterMarkHandler.DEFAULT);
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

    public static void writeAndFlush(@NonNull Channel channel, @NonNull Queue<?> packs) {
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

    public static void closeOnFlushed(@NonNull Channel channel) {
        if (!channel.isActive()) {
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

    //region Address
    public static String loopbackHostAddress() {
        if (loopbackAddr == null) {
            loopbackAddr = loopbackAddress().getHostAddress();
        }
        return loopbackAddr;
    }

    public static InetAddress loopbackAddress() {
        return InetAddress.getLoopbackAddress();
    }

    @SneakyThrows
    public static InetAddress anyLocalAddress() {
        return InetAddress.getByName("0.0.0.0");
    }

    @SneakyThrows
    public static boolean isLanIp(InetAddress ip) {
        String hostAddress = ip.getHostAddress();
        if (Strings.hashEquals(loopbackHostAddress(), hostAddress)) {
            return true;
        }
        for (String regex : RxConfig.INSTANCE.getNet().getLanIps()) {
            if (Pattern.matches(regex, hostAddress)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidIp(String ip) {
        return NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip);
    }

    @SneakyThrows
    public static InetAddress getLocalAddress() {
        Inet4Address first = Linq.from(getLocalAddresses()).orderByDescending(p -> p.isSiteLocalAddress()).firstOrDefault();
        if (first != null) {
            return first;
        }
        return InetAddress.getLocalHost(); //可能返回127.0.0.1
    }

    @SneakyThrows
    public static List<Inet4Address> getLocalAddresses() {
        List<Inet4Address> ips = new ArrayList<>();
        Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
        while (networks.hasMoreElements()) {
            NetworkInterface network = networks.nextElement();
            if (network.isLoopback() || network.isVirtual() || !network.isUp()) {
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
        return ips;
    }

    public static InetSocketAddress localEndpoint(int port) {
        return new InetSocketAddress(loopbackAddress(), port);
    }

    public static InetSocketAddress anyEndpoint(int port) {
        return new InetSocketAddress(anyLocalAddress(), port);
    }

    //check jdk11 unknown host
    public static InetSocketAddress parseEndpoint(@NonNull String endpoint) {
        int i = endpoint.lastIndexOf(":");
        if (i == -1) {
            throw new InvalidException("Invalid endpoint {}", endpoint);
        }

        String ip = endpoint.substring(0, i);
        int port = Integer.parseInt(endpoint.substring(i + 1));
        return new InetSocketAddress(ip, port);
//        return InetSocketAddress.createUnresolved(ip, port);  //DNS解析有问题
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
    public static List<InetSocketAddress> allEndpoints(@NonNull InetSocketAddress endpoint) {
        return Linq.from(InetAddress.getAllByName(endpoint.getHostString())).select(p -> new InetSocketAddress(p, endpoint.getPort())).toList();
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
            //如"localhost|192.168.0.*"
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
