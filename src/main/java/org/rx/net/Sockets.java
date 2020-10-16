package org.rx.net;

import java.net.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.BootstrapConfig;
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
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.*;
import org.rx.core.exception.InvalidException;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.rx.core.Contract.*;

@Slf4j
public final class Sockets {
    public static void writeAndFlush(Channel channel, List<Object> packs) {
        require(channel);

        channel.eventLoop().execute(() -> {
            for (Object pack : packs) {
                channel.write(pack);
            }
            channel.flush();
        });
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

    public static void closeOnFlushed(Channel channel) {
        closeOnFlushed(channel, null);
    }

    public static void closeOnFlushed(Channel channel, ChannelFutureListener futureListener) {
        require(channel);

        if (!channel.isActive()) {
            return;
        }
        ChannelFuture future = channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        if (futureListener != null) {
            future.addListener(futureListener);
        }
    }

    public static Bootstrap bootstrap(boolean tryEpoll) {
        return bootstrap(tryEpoll, null, null, null);
    }

    public static Bootstrap bootstrap(boolean tryEpoll, Channel channel, MemoryMode mode, Consumer<SocketChannel> initChannel) {
        Class<? extends Channel> channelClass = channel != null ? channel.getClass() : channelClass(tryEpoll);
        boolean isEpoll = EpollSocketChannel.class.isAssignableFrom(channelClass);
        Bootstrap b = new Bootstrap()
                .group(channel != null ? channel.eventLoop() :
                        isEpoll ? new EpollEventLoopGroup() : new NioEventLoopGroup())
                .channel(channelClass)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONFIG.getNetTimeoutMillis())
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

    public static void closeBootstrap(Bootstrap bootstrap) {
        if (bootstrap == null) {
            return;
        }

        BootstrapConfig config = bootstrap.config();
        if (config.group() != null) {
            config.group().shutdownGracefully();
        }
    }

    public static ServerBootstrap serverBootstrap(boolean tryEpoll) {
        return serverBootstrap(tryEpoll, 1, 0, null, null);
    }

    public static ServerBootstrap serverBootstrap(boolean tryEpoll, int bossThreadAmount, int workThreadAmount, MemoryMode mode, Consumer<SocketChannel> initChannel) {
        ServerBootstrap b = new ServerBootstrap()
                .group(eventLoopGroup(tryEpoll, bossThreadAmount), eventLoopGroup(tryEpoll, workThreadAmount))
                .channel(serverChannelClass(tryEpoll))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONFIG.getNetTimeoutMillis())
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

    private static Class<? extends SocketChannel> channelClass(boolean tryEpoll) {
        return epoll(tryEpoll) ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    private static Class<? extends ServerSocketChannel> serverChannelClass(boolean tryEpoll) {
        return epoll(tryEpoll) ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    private static EventLoopGroup eventLoopGroup(boolean tryEpoll, int threadAmount) {
        Class<? extends EventLoopGroup> eventLoopGroupClass = epoll(tryEpoll) ? EpollEventLoopGroup.class : NioEventLoopGroup.class;
        return Reflects.newInstance(eventLoopGroupClass, threadAmount);
//        return Reflects.newInstance(eventLoopGroupClass, threadAmount, Tasks.getExecutor());
    }

    private static boolean epoll(boolean enable) {
        return enable && Epoll.isAvailable();
    }

    //region Address
    public static final InetAddress LoopbackAddress, AnyAddress;

    static {
        LoopbackAddress = InetAddress.getLoopbackAddress();
        try {
            AnyAddress = InetAddress.getByName("0.0.0.0");
        } catch (Exception e) {
            throw InvalidException.sneaky(e);
        }
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

    public static InetSocketAddress parseEndpoint(String endpoint) {
        require(endpoint);

        String[] arr = Strings.split(endpoint, ":", 2);
        return new InetSocketAddress(arr[0], Integer.parseInt(arr[1]));
    }

    public static InetSocketAddress newEndpoint(String endpoint, int port) {
        return newEndpoint(parseEndpoint(endpoint), port);
    }

    public static InetSocketAddress newEndpoint(InetSocketAddress endpoint, int port) {
        require(endpoint);

        return new InetSocketAddress(endpoint.getAddress(), port);
    }

    public static String toString(InetSocketAddress endpoint) {
        return String.format("%s:%s", endpoint.getHostString(), endpoint.getPort());
    }

    public static void closeOnFlushed(Socket socket) {
        require(socket);
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

    static class UserAuthenticator extends Authenticator {
        private String userName;
        private String password;

        public UserAuthenticator(String userName, String password) {
            this.userName = userName;
            this.password = password;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(userName, password.toCharArray());
        }
    }
    //endregion
}
