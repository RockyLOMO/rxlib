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
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.core.exception.InvalidException;
import org.springframework.util.CollectionUtils;

import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.rx.core.App.*;

@Slf4j
public final class Sockets {
    static final Map<String, EventLoopGroup> shared = new ConcurrentHashMap<>();

    public static EventLoopGroup sharedEventLoop(@NonNull String groupName) {
        return shared.computeIfAbsent(groupName, k -> Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup());
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

    public static void writeAndFlush(@NonNull Channel channel, List<Object> packs) {
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

    public static Bootstrap bootstrap(String groupName, Consumer<SocketChannel> initChannel) {
        return bootstrap(sharedEventLoop(groupName), null, initChannel);
    }

    public static Bootstrap bootstrap(@NonNull EventLoopGroup eventLoopGroup, MemoryMode mode, Consumer<SocketChannel> initChannel) {
        Bootstrap b = new Bootstrap()
                .group(eventLoopGroup)
                .channel(channelClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, App.getConfig().getNetTimeoutMillis())
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true);
        if (mode != null) {
            b.option(ChannelOption.SO_SNDBUF, mode.getSendBuf())
                    .option(ChannelOption.SO_RCVBUF, mode.getReceiveBuf());
        }
        if (initChannel != null) {
            b.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socketChannel) throws Exception {
                    initChannel.accept(socketChannel);
                }
            });
        }
        return b;
    }

    public static ServerBootstrap serverBootstrap() {
        return serverBootstrap(0, null, null);
    }

    public static ServerBootstrap serverBootstrap(int workThreadAmount, MemoryMode mode, Consumer<SocketChannel> initChannel) {
        int bossThreadAmount = 1; //等于bind的次数，默认1
        ServerBootstrap b = new ServerBootstrap()
                .group(eventLoopGroup(bossThreadAmount), eventLoopGroup(workThreadAmount))
                .channel(serverChannelClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, App.getConfig().getNetTimeoutMillis())
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
//                    .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        if (mode != null) {
            b.option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(mode.getLowWaterMark(), mode.getHighWaterMark()))
                    .option(ChannelOption.SO_BACKLOG, mode.getBacklog())
                    .childOption(ChannelOption.SO_SNDBUF, mode.getSendBuf())
                    .childOption(ChannelOption.SO_RCVBUF, mode.getReceiveBuf());
        }
        //netty日志
        b.handler(new LoggingHandler(LogLevel.INFO));
        if (initChannel != null) {
            b.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel socketChannel) throws Exception {
                    initChannel.accept(socketChannel);
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
        EventLoopGroup bossGroup = config.group();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        EventLoopGroup workerGroup = config.childGroup();
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    private static Class<? extends SocketChannel> channelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    private static Class<? extends ServerSocketChannel> serverChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    private static EventLoopGroup eventLoopGroup(int threadAmount) {
        Class<? extends EventLoopGroup> eventLoopGroupClass = Epoll.isAvailable() ? EpollEventLoopGroup.class : NioEventLoopGroup.class;
        return Reflects.newInstance(eventLoopGroupClass, threadAmount);
//        return Reflects.newInstance(eventLoopGroupClass, threadAmount, Tasks.getExecutor());
    }

    //region Address
    public static final InetAddress LoopbackAddress = InetAddress.getLoopbackAddress(),
            AnyAddress = quietly(() -> InetAddress.getByName("0.0.0.0"));

    public static List<String> getDnsRecords(String domain, String[] types) {
//        InetAddress.getByName(ddns).getHostAddress()
        return getDnsRecords(domain, types, "114.114.114.114", 10, 2);
    }

    @SneakyThrows
    public static List<String> getDnsRecords(String domain, String[] types, String dnsServer, int timeoutSeconds, int retryCount) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        //设置域名服务器
        env.put(Context.PROVIDER_URL, "dns://" + dnsServer);
        //连接时间
        env.put("com.sun.jndi.dns.timeout.initial", String.valueOf(timeoutSeconds * 1000));
        //连接次数
        env.put("com.sun.jndi.dns.timeout.retries", String.valueOf(retryCount));
        DirContext ctx = new InitialDirContext(env);
        Enumeration<?> e = ctx.getAttributes(domain, types).getAll();
        List<String> result = new ArrayList<>();
        while (e.hasMoreElements()) {
            Attribute attr = (Attribute) e.nextElement();
            int size = attr.size();
            for (int i = 0; i < size; i++) {
                result.add((String) attr.get(i));
            }
        }
        return result;
    }

    //InetAddress.getLocalHost(); 可能会返回127.0.0.1
    @SneakyThrows
    public static Inet4Address getLocalAddress() {
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
                    return (Inet4Address) address;
                }
                candidateAddress = (Inet4Address) address;
            }
        }
        if (candidateAddress == null) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 53);
                if (socket.getLocalAddress() instanceof Inet4Address) {
                    return (Inet4Address) socket.getLocalAddress();
                }
            }
        }
        throw new InvalidException("LAN IP not found");
    }

    public InetAddress[] getAddresses(String host) {
        return Cache.getOrSet(cacheKey("getAddresses", host), p -> InetAddress.getAllByName(host));
    }

    public static InetSocketAddress getAnyEndpoint(int port) {
        return new InetSocketAddress(AnyAddress, port);
    }

    public static InetSocketAddress parseEndpoint(@NonNull String endpoint) {
        String[] arr = Strings.split(endpoint, ":", 2);
        return new InetSocketAddress(arr[0], Integer.parseInt(arr[1]));
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
