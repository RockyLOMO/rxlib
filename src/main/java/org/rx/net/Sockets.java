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
import org.rx.bean.RxConfig;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.exception.InvalidException;
import org.rx.net.dns.DnsClient;
import org.rx.util.function.BiAction;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.rx.bean.$.$;
import static org.rx.core.App.*;

@Slf4j
public final class Sockets {
    public static final LengthFieldPrepender INT_LENGTH_PREPENDER = new LengthFieldPrepender(4);
    static final List<String> DEFAULT_NAT_IPS = Arrays.toList("127.0.0.1", "[::1]", "localhost", "192.168.*");
    static final LoggingHandler DEFAULT_LOG = new LoggingHandler(LogLevel.INFO);
    static final String SERVER_REACTOR = "_SHARED";
    static final String UDP_REACTOR = "_UDP";
    static final Map<String, MultithreadEventLoopGroup> reactors = new ConcurrentHashMap<>();
    static final ReentrantLock nsLock = new ReentrantLock(true);
    static DnsClient nsClient;

    @SneakyThrows
    public static void injectNameService(List<InetSocketAddress> nameServerList) {
        DnsClient client = CollectionUtils.isEmpty(nameServerList) ? DnsClient.inlandClient() : new DnsClient(nameServerList);
        nsLock.lock();
        try {
            if (nsClient == null) {
                Class<?> type = InetAddress.class;
                try {
                    Field field = type.getDeclaredField("nameService");
                    Reflects.setAccess(field);
                    field.set(null, nsProxy(field.get(null), client));
                } catch (NoSuchFieldException e) {
                    Field field = type.getDeclaredField("nameServices");
                    Reflects.setAccess(field);
                    List<Object> nsList = (List<Object>) field.get(null);
                    nsList.set(0, nsProxy(nsList.get(0), client));
                }
            }
            nsClient = client;
        } finally {
            nsLock.unlock();
        }
    }

    private static Object nsProxy(Object ns, DnsClient client) {
        Class<?> type = ns.getClass();
        InetAddress[] empty = new InetAddress[0];
        return Proxy.newProxyInstance(type.getClassLoader(), type.getInterfaces(), (pObject, method, args) -> {
            if (method.getName().equals("lookupAllHostAddr")) {
                String host = (String) args[0];
                //处理不了会交给源对象处理
                try {
                    List<InetAddress> addresses = client.resolveAll(host);
                    if (!CollectionUtils.isEmpty(addresses)) {
                        return addresses.toArray(empty);
                    }
                } catch (Exception e) {
                    log.info("nsProxy {}", e.getMessage());
                }
            }
            Reflects.setAccess(method);
            return method.invoke(ns, args);
        });
    }

    static EventLoopGroup reactor(@NonNull String reactorName) {
        return reactors.computeIfAbsent(reactorName, k -> {
            int amount = Container.get(RxConfig.class).getSharedReactorThreadAmount();
            return Epoll.isAvailable() ? new EpollEventLoopGroup(amount) : new NioEventLoopGroup(amount);
        });
    }

    public static EventLoopGroup udpReactor() {
        return reactors.computeIfAbsent(UDP_REACTOR, k -> new NioEventLoopGroup());
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
                .group(newEventLoop(bossThreadAmount), config.isUseSharedTcpEventLoop() ? reactor(SERVER_REACTOR) : newEventLoop(0))
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
                .handler(WaterMarkHandler.DEFAULT);
        if (config.isEnableNettyLog()) {
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
        return bootstrap(SERVER_REACTOR, null, initChannel);
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
        if (config.isEnableNettyLog()) {
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

    public static Bootstrap udpBootstrap(BiAction<NioDatagramChannel> initChannel) {
        return udpBootstrap(null, initChannel);
    }

    //DefaultDatagramChannelConfig
    public static Bootstrap udpBootstrap(MemoryMode mode, BiAction<NioDatagramChannel> initChannel) {
        if (mode == null) {
            mode = MemoryMode.LOW;
        }

        Bootstrap b = new Bootstrap().group(udpReactor()).channel(NioDatagramChannel.class)
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
        if (msg instanceof io.netty.channel.socket.DatagramPacket) {
            return ((DatagramPacket) msg).content();
        } else if (msg instanceof ByteBuf) {
            return (ByteBuf) msg;
        } else {
            throw new InvalidException("unsupported msg type:" + msg.getClass());
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
    public static InetAddress loopbackAddress() {
        return InetAddress.getLoopbackAddress();
    }

    @SneakyThrows
    public static InetAddress anyLocalAddress() {
        return InetAddress.getByName("0.0.0.0");
    }

    @SneakyThrows
    public static boolean isNatIp(InetAddress ip) {
        if (eq(loopbackAddress(), ip)) {
            return true;
        }

        String hostAddress = ip.getHostAddress();
        for (String regex : DEFAULT_NAT_IPS) {
            if (Pattern.matches(regex, hostAddress)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidIp(String ip) {
        return NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip);
    }

    //InetAddress.getLocalHost(); 可能会返回127.0.0.1
    @SneakyThrows
    public static InetAddress getLocalAddress() {
        Inet4Address candidateAddress = null;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (networkInterface.isLoopback() || networkInterface.isVirtual() || !networkInterface.isUp()) {
                continue;
            }
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (address.isLoopbackAddress() || !(address instanceof Inet4Address)) {
                    continue;
                }
                if (address.isSiteLocalAddress()) {
                    return address;
                }
                candidateAddress = (Inet4Address) address;
            }
        }
        if (candidateAddress != null) {
            return candidateAddress;
        }
        return InetAddress.getLocalHost();
    }

    public static InetSocketAddress localEndpoint(int port) {
        return new InetSocketAddress(loopbackAddress(), port);
    }

    public static InetSocketAddress anyEndpoint(int port) {
        return new InetSocketAddress(anyLocalAddress(), port);
    }

    public static InetSocketAddress parseEndpoint(@NonNull String endpoint) {
        int i = endpoint.lastIndexOf(":");
        if (i == -1) {
            throw new InvalidException("Invalid endpoint %s", endpoint);
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
        return NQuery.of(InetAddress.getAllByName(endpoint.getHostString())).select(p -> new InetSocketAddress(p, endpoint.getPort())).toList();
    }

    public static String toString(InetSocketAddress endpoint) {
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

    public static NQuery<SocketInfo> socketInfos(SocketProtocol protocol) {
        try (ShellCommander cmd = new ShellCommander("netstat -aon")) {
            List<SocketInfo> list = new ArrayList<>();
            cmd.start(l -> {
                String line = l.getLine();
                if (!line.contains(protocol.name())) {
                    return;
                }

                String[] arr = NQuery.of(Strings.split(line, "  ")).select(String::trim).where(p -> !p.isEmpty()).toArray();
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
                } catch (Exception e) {
                    log.error("Parse line {} fail", toJsonString(arr), e);
                }
            });
            cmd.waitFor();

            NQuery<SocketInfo> q = NQuery.of(list, true);
            q.forEach(p -> p.setProcessName(processName(p.pid)));
            return q;
        }
    }

    static String processName(long pid) {
        return Cache.getOrSet(cacheKey("processName:", pid), k -> {
            $<String> name = $();
            try (ShellCommander cmd = new ShellCommander(String.format("tasklist /fi \"pid eq %s\"", pid))) {
                String t = String.format(" %s", pid);
                cmd.start(l -> {
                    int i = l.getLine().indexOf(t);
                    if (i == -1) {
                        return;
                    }
                    name.v = l.getLine().substring(0, i).trim();
                });
                cmd.waitFor();
            }
            return name.v;
        });
    }
    //#endregion

    //region httpProxy
    public static <T> T httpProxyInvoke(String proxyAddr, Function<String, T> func) {
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
