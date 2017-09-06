package org.rx.socket;

import org.rx.util.App;

import java.net.*;
import java.util.List;
import java.util.Properties;

import static org.rx.common.Contract.require;
import static org.rx.common.Contract.wrapCause;

public final class Sockets {
    public static final InetAddress LocalAddress, AnyAddress;

    static {
        LocalAddress = InetAddress.getLoopbackAddress();
        try {
            AnyAddress = InetAddress.getByName("0.0.0.0");
        } catch (Exception ex) {
            throw wrapCause(ex);
        }
    }

    public static String getId(Socket sock, boolean isRemote) {
        require(sock);

        InetSocketAddress addr = (InetSocketAddress) (isRemote ? sock.getRemoteSocketAddress()
                : sock.getLocalSocketAddress());
        return addr.getHostString() + ":" + addr.getPort();
    }

    public static InetSocketAddress parseAddress(String sockAddr) {
        require(sockAddr);
        String[] arr = sockAddr.split(":");
        require(arr, p -> p.length == 2);

        return new InetSocketAddress(arr[0], Integer.parseInt(arr[1]));
    }

    public static void setHttpProxy(String sockAddr) {
        setHttpProxy(sockAddr, null, null, null);
    }

    public static void setHttpProxy(String sockAddr, List<String> nonProxyHosts, String userName, String password) {
        InetSocketAddress ipe = parseAddress(sockAddr);
        Properties prop = System.getProperties();
        prop.setProperty("http.proxyHost", ipe.getAddress().getHostAddress());
        prop.setProperty("http.proxyPort", String.valueOf(ipe.getPort()));
        prop.setProperty("https.proxyHost", ipe.getAddress().getHostAddress());
        prop.setProperty("https.proxyPort", String.valueOf(ipe.getPort()));
        if (!App.isNullOrEmpty(nonProxyHosts)) {
            //如"localhost|192.168.0.*"
            prop.setProperty("http.nonProxyHosts", String.join("|", nonProxyHosts));
        }
        if (userName != null && password != null) {
            Authenticator.setDefault(new UserAuthenticator(userName, password));
        }
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
