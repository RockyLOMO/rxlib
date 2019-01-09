package org.rx.socks;

import java.io.IOException;
import java.net.*;
import org.rx.common.SystemException;
import org.rx.cache.WeakCache;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Properties;
import java.util.function.Function;

import static org.rx.common.Contract.require;

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
        return (InetAddress[]) WeakCache.getOrStore(Sockets.class, host, p -> {
            try {
                return InetAddress.getAllByName(p);
            } catch (UnknownHostException ex) {
                throw SystemException.wrap(ex);
            }
        });
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
     * @param flags Send=1, Receive=2
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

    public static InetSocketAddress parseAddress(String endpoint) {
        require(endpoint);
        String[] arr = endpoint.split(":");
        require(arr, arr.length == 2);

        return new InetSocketAddress(arr[0], Integer.parseInt(arr[1]));
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
        InetSocketAddress ipe = parseAddress(proxyAddr);
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
