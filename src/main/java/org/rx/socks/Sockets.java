package org.rx.socks;

import java.io.IOException;
import java.net.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.rx.core.Strings;
import org.rx.core.SystemException;
import org.rx.core.WeakCache;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Properties;
import java.util.function.Function;

import static org.rx.core.Contract.require;

public final class Sockets {
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
        String prefix = "Sockets-";
        return (InetAddress[]) WeakCache.getInstance().getOrAdd(prefix + host, p -> {
            try {
                return InetAddress.getAllByName(p.substring(prefix.length()));
            } catch (UnknownHostException ex) {
                throw SystemException.wrap(ex);
            }
        });
    }

    public static InetSocketAddress getAnyEndpoint(int port) {
        return new InetSocketAddress(AnyAddress, port);
    }

    public static InetSocketAddress parseEndpoint(String endpoint) {
        require(endpoint);

        String[] arr = Strings.split(endpoint, ":", 2);
        return new InetSocketAddress(arr[0], Integer.parseInt(arr[1]));
    }

    public static EventLoopGroup bossEventLoop() {
        return eventLoopGroup(1);
    }

    public static EventLoopGroup workEventLoop() {
        return eventLoopGroup(0);
    }

    public static EventLoopGroup eventLoopGroup(int threadAmount) {
        return Epoll.isAvailable() ? new EpollEventLoopGroup(threadAmount) : new NioEventLoopGroup(threadAmount);  //NioEventLoopGroup(0, TaskFactory.getExecutor());
    }

    public static Bootstrap bootstrap() {
        return bootstrap(getChannelClass());
    }

    public static Bootstrap bootstrap(Class<? extends Channel> channelClass) {
        require(channelClass);

        return new Bootstrap().group(channelClass.getName().startsWith("Epoll") ? new EpollEventLoopGroup() : new NioEventLoopGroup()).channel(channelClass);
    }

    public static Bootstrap bootstrap(Channel channel) {
        require(channel);

        return new Bootstrap().group(channel.eventLoop()).channel(channel.getClass());
    }

    public static Class<? extends ServerChannel> getServerChannelClass() {
        return Epoll.isAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }

    public static Class<? extends Channel> getChannelClass() {
        return Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class;
    }

    public static void closeOnFlushed(Channel channel) {
        require(channel);

        if (!channel.isActive()) {
            return;
        }
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    public static void close(Socket socket) {
        close(socket, 1 | 2);
    }

    public static void close(Socket socket, int flags) {
        require(socket);

        if (!socket.isClosed()) {
            shutdown(socket, flags);
            try {
                socket.setSoLinger(true, 2);
                socket.close();
            } catch (IOException ex) {
                throw SystemException.wrap(ex);
            }
        }
    }

    /**
     * @param socket
     * @param flags  Send=1, Receive=2
     */
    public static void shutdown(Socket socket, int flags) {
        require(socket);

        if (!socket.isClosed() && socket.isConnected()) {
            try {
                if ((flags & 1) == 1 && !socket.isOutputShutdown()) {
                    socket.shutdownOutput();
                }
                if ((flags & 2) == 2 && !socket.isInputShutdown()) {
                    socket.shutdownInput();
                }
            } catch (IOException ex) {
                throw SystemException.wrap(ex);
            }
        }
    }

    public static String getId(Socket sock, boolean isRemote) {
        require(sock);

        InetSocketAddress addr = (InetSocketAddress) (isRemote ? sock.getRemoteSocketAddress()
                : sock.getLocalSocketAddress());
        return addr.getHostString() + ":" + addr.getPort();
    }

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
}
