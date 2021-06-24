package org.rx.net;

import java.net.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.bootstrap.ServerBootstrapConfig;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.NetUtil;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.core.Arrays;
import org.rx.net.dns.DnsClient;
import org.rx.util.function.BiAction;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.rx.core.App.*;

@Slf4j
public final class Sockets {
    static final Map<String, EventLoopGroup> reactors = new ConcurrentHashMap<>();
    //    static final TaskScheduler scheduler = new TaskScheduler("EventLoop");
    @Getter(lazy = true)
    private static final NioEventLoopGroup udpEventLoop = new NioEventLoopGroup();
    @Getter(lazy = true)
    private static final DnsClient dnsClient = DnsClient.inlandServerList();

    static EventLoopGroup reactorEventLoop(@NonNull String reactorName) {
        return reactors.computeIfAbsent(reactorName, k -> Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup());
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
        AdaptiveRecvByteBufAllocator recvByteBufAllocator = new AdaptiveRecvByteBufAllocator(64, 2048, mode.getReceiveBufMaximum());
        WriteBufferWaterMark writeBufferWaterMark = new WriteBufferWaterMark(mode.getSendBufLowWaterMark(), mode.getSendBufHighWaterMark());
        ServerBootstrap b = new ServerBootstrap()
                .group(newEventLoop(bossThreadAmount), newEventLoop(0))
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
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark);
        if (config.isEnableNettyLog()) {
            //netty日志
            b.handler(new LoggingHandler(LogLevel.INFO));
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
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    public static Bootstrap udpBootstrap() {
        return udpBootstrap(getUdpEventLoop(), false);
    }

    public static Bootstrap udpBootstrap(@NonNull EventLoopGroup eventLoopGroup, boolean broadcast) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup).channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, broadcast)
                .handler(new LoggingHandler(LogLevel.INFO));
        return bootstrap;
    }

    public static Bootstrap bootstrap(BiAction<SocketChannel> initChannel) {
        return bootstrap(Strings.EMPTY, null, initChannel);
    }

    public static Bootstrap bootstrap(@NonNull String reactorName, SocketConfig config, BiAction<SocketChannel> initChannel) {
        return bootstrap(reactorEventLoop(reactorName), config, initChannel);
    }

    public static Bootstrap bootstrap(@NonNull EventLoopGroup eventLoopGroup, SocketConfig config, BiAction<SocketChannel> initChannel) {
        if (config == null) {
            config = new SocketConfig();
        }
        MemoryMode mode = config.getMemoryMode();
        int connectTimeoutMillis = config.getConnectTimeoutMillis();
        boolean highNetwork = connectTimeoutMillis <= SocketConfig.DELAY_TIMEOUT_MILLIS;

        AdaptiveRecvByteBufAllocator recvByteBufAllocator = new AdaptiveRecvByteBufAllocator(64, 2048, mode.getReceiveBufMaximum());
        WriteBufferWaterMark writeBufferWaterMark = new WriteBufferWaterMark(mode.getSendBufLowWaterMark(), mode.getSendBufHighWaterMark());
        Bootstrap b = new Bootstrap()
                .group(eventLoopGroup)
                .channel(channelClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .option(ChannelOption.TCP_NODELAY, highNetwork)
//                .option(ChannelOption.SO_KEEPALIVE, !highNetwork)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.RCVBUF_ALLOCATOR, recvByteBufAllocator)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK, writeBufferWaterMark);
        if (config.isEnableNettyLog()) {
            b.handler(new LoggingHandler(LogLevel.INFO));
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

    public static ChannelFutureListener logBind(int port) {
        return f -> {
            String ch = f.channel() instanceof NioDatagramChannel ? "UDP" : "TCP";
            if (!f.isSuccess()) {
                log.error("{} Listen on port {} fail", ch, port, f.cause());
                return;
            }
            log.info("{} Listened on {}", ch, f.channel().localAddress());
        };
    }

    public static  ChannelFutureListener logConnect(InetSocketAddress endpoint){

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

    public static void writeAndFlush(@NonNull Channel channel, @NonNull Collection<Object> packs) {
        Queue<Object> queue = as(packs, Queue.class);
        if (queue != null) {
            channel.eventLoop().execute(() -> {
                Object pack;
                while ((pack = queue.poll()) != null) {
                    channel.write(pack);
                }
                channel.flush();
            });
            return;
        }

        channel.eventLoop().execute(() -> {
            for (Object pack : packs) {
                channel.write(pack);
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

    //region Address
    public static final InetAddress LOOPBACK_ADDRESS = InetAddress.getLoopbackAddress(),
            ANY_ADDRESS = quietly(() -> InetAddress.getByName("0.0.0.0"));

    @SneakyThrows
    public static boolean isNatIp(InetAddress address) {
        return eq(LOOPBACK_ADDRESS, address) || eq(InetAddress.getLocalHost(), address)
                || address.getHostAddress().startsWith("192.168.");
    }

    public static boolean isValidIp(String ipV4OrIpV6) {
        return NetUtil.isValidIpV4Address(ipV4OrIpV6) || NetUtil.isValidIpV6Address(ipV4OrIpV6);
    }

    public static List<InetAddress> resolveAddresses(String host) {
        return getDnsClient().resolveAll(host);
    }

    public static List<InetAddress> getAddresses(String host) {
        return Cache.getOrSet(cacheKey("getAddresses", host), p -> Arrays.toList(InetAddress.getAllByName(host)));
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
//        throw new InvalidException("LAN IP not found");
        return InetAddress.getLocalHost();
    }

    public static InetSocketAddress getAnyEndpoint(int port) {
        return new InetSocketAddress(ANY_ADDRESS, port);
    }

    public static InetSocketAddress parseEndpoint(@NonNull String endpoint) {
        String[] pair = Strings.split(endpoint, ":", 2);
        String ip = pair[0];
        int port = Integer.parseInt(pair[1]);
//        if (isValidIp(ip)) {
//            return new InetSocketAddress(ip, port);
//        }
//        return InetSocketAddress.createUnresolved(ip, port);  //DNS解析有问题
        return new InetSocketAddress(ip, port);
    }

    public static InetSocketAddress newEndpoint(String endpoint, int port) {
        return newEndpoint(parseEndpoint(endpoint), port);
    }

    public static InetSocketAddress newEndpoint(@NonNull InetSocketAddress endpoint, int port) {
        return new InetSocketAddress(endpoint.getAddress(), port);
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
