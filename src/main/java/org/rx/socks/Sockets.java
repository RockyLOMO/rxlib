package org.rx.socks;

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
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.rx.core.*;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.rx.core.App.Config;
import static org.rx.core.Contract.*;

public final class Sockets {
    public static final ChannelFutureListener FireExceptionThenCloseOnFailure = f -> {
        if (!f.isSuccess()) {
            f.channel().pipeline().fireExceptionCaught(f.cause());
            f.channel().close();
        }
    };
    private static final Lazy<EventLoopGroup> WorkGroup = new Lazy<>(() -> Sockets.eventLoopGroup(0));

    public static void writeAndFlush(Channel channel, List<Object> packs) {
        require(channel);

        channel.eventLoop().execute(() -> {
            for (Object pack : packs) {
                channel.write(pack);
            }
            channel.flush();
        });
    }

    public static void closeOnFlushed(Channel channel) {
        require(channel);

        if (!channel.isActive()) {
            return;
        }
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    public static Bootstrap bootstrap() {
        return bootstrap(channelClass(), null, null, null);
    }

    public static Bootstrap bootstrap(Class<? extends Channel> channelClass, Channel channel, MemoryMode mode, Consumer<SocketChannel> initChannel) {
        require(channelClass);

        Bootstrap b = new Bootstrap()
                .group(channel != null ? channel.eventLoop() :
                        channelClass == channelClass() ? WorkGroup.getValue() :
                                channelClass.getName().startsWith("Epoll") ? new EpollEventLoopGroup() : new NioEventLoopGroup())
                .channel(channel != null ? channel.getClass() : channelClass);
        if (EpollServerSocketChannel.class.isAssignableFrom(channelClass)) {
            b.option(EpollChannelOption.CONNECT_TIMEOUT_MILLIS, Config.getSocksTimeout())
                    .option(EpollChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(EpollChannelOption.TCP_NODELAY, true)
                    .option(EpollChannelOption.SO_KEEPALIVE, true);
            if (mode != null) {
                b.option(EpollChannelOption.SO_SNDBUF, mode.getSendBuf())
                        .option(EpollChannelOption.SO_RCVBUF, mode.getReceiveBuf());
            }
        } else {
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Config.getSocksTimeout())
                    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true);
            if (mode != null) {
                b.option(ChannelOption.SO_SNDBUF, mode.getSendBuf())
                        .option(ChannelOption.SO_RCVBUF, mode.getReceiveBuf());
            }
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
            if (WorkGroup.isValueCreated() && config.group() == WorkGroup.getValue()) {
                return;
            }
            config.group().shutdownGracefully();
        }
    }

    public static ServerBootstrap serverBootstrap() {
        return serverBootstrap(1, 0, null, null);
    }

    public static ServerBootstrap serverBootstrap(int bossThreadAmount, int workThreadAmount, MemoryMode mode, Consumer<SocketChannel> initChannel) {
        Class<? extends ServerChannel> channelClass = serverChannelClass();
        ServerBootstrap b = new ServerBootstrap()
                .group(eventLoopGroup(bossThreadAmount), eventLoopGroup(workThreadAmount))
                .channel(channelClass);
        if (EpollServerSocketChannel.class.isAssignableFrom(channelClass)) {
            b.option(EpollChannelOption.CONNECT_TIMEOUT_MILLIS, Config.getSocksTimeout())
                    .option(EpollChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(EpollChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(EpollChannelOption.TCP_NODELAY, true)
                    .childOption(EpollChannelOption.SO_KEEPALIVE, true);
            if (mode != null) {
                b.option(EpollChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(mode.getLowWaterMark(), mode.getHighWaterMark()))
                        .option(EpollChannelOption.SO_BACKLOG, mode.getBacklog())
                        .childOption(EpollChannelOption.SO_SNDBUF, mode.getSendBuf())
                        .childOption(EpollChannelOption.SO_RCVBUF, mode.getReceiveBuf());
            }
        } else {
            b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Config.getSocksTimeout())
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
        }
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
        EventLoopGroup workerGroup = config.childGroup();
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        EventLoopGroup bossGroup = config.group();
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    public static EventLoopGroup eventLoopGroup(int threadAmount) {
        return eventLoopGroup(threadAmount, Epoll.isAvailable() ? EpollEventLoopGroup.class : NioEventLoopGroup.class);
    }

    public static EventLoopGroup eventLoopGroup(int threadAmount, Class<? extends EventLoopGroup> eventLoopGroupClass) {
        return Reflects.newInstance(eventLoopGroupClass, threadAmount);
//        return Reflects.newInstance(eventLoopGroupClass, threadAmount, Tasks.getExecutor());
    }

    public static Class<? extends ServerChannel> serverChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    public static Class<? extends Channel> channelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    //region Address
    public static final InetAddress LocalAddress, AnyAddress;

    static {
        LocalAddress = InetAddress.getLoopbackAddress();
        try {
            AnyAddress = InetAddress.getByName("0.0.0.0");
        } catch (Exception ex) {
            throw SystemException.wrap(ex);
        }
    }

    public InetAddress[] getAddresses(String host) {
        return (InetAddress[]) WeakCache.getOrStore(cacheKey("Sockets.getAddresses", host), p -> InetAddress.getAllByName(host));
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

        catchCall(() -> {
            if (socket.isConnected()) {
                if (!socket.isOutputShutdown()) {
                    socket.shutdownOutput();
                }
                if (!socket.isInputShutdown()) {
                    socket.shutdownInput();
                }
            }
            socket.setSoLinger(true, 2);
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

        protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(userName, password.toCharArray());
        }
    }
    //endregion
}
