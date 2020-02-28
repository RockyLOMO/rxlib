package org.rx.jdbc;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.rx.socks.Sockets;

import java.io.Serializable;
import java.net.InetSocketAddress;

@Data
public class JdbcConfig implements Serializable {
    private String url;
    private String username;
    private String password;

    private int connectionTimeoutMilliseconds = 20000;
    private int idleTimeoutMilliseconds = 60000;
    private int maxLifetimeMilliseconds = 1800000;
    private int executeTimeoutMilliseconds = 60000;
    private int minPoolSize = 1, maxPoolSize = 32;
    private JdbcConnectionPool pool = JdbcConnectionPool.HikariCP;
    private int weight = 10;

    @Getter(AccessLevel.PRIVATE)
    @Setter(AccessLevel.PRIVATE)
    private InetSocketAddress endpoint;
    @Getter(AccessLevel.PRIVATE)
    @Setter(AccessLevel.PRIVATE)
    private String databaseName;

    public void setUrl(String url) {
        this.url = url;
        endpoint = null;
        databaseName = null;
    }

    public InetSocketAddress getEndpointFromUrl() {
        if (endpoint == null) {
            endpoint = getEndpointFromUrl(url);
        }
        return endpoint;
    }

    public String getDatabaseNameFromUrl() {
        if (databaseName == null) {
            databaseName = getDatabaseNameFromUrl(url);
        }
        return databaseName;
    }

    public static InetSocketAddress getEndpointFromUrl(String url) {
        return Sockets.parseEndpoint(findChars(url, "://", "/", 0));
    }

    public static String getDatabaseNameFromUrl(String url) {
        return findChars(url, "/", "?", url.indexOf("://") + 3);
    }

    private static String findChars(String url, String begin, String end, int startIndex) {
        int s = url.indexOf(begin, startIndex);
        if (s == -1) {
//            throw new InvalidOperationException("begin flag not found");
            return null;
        }
        int offset = s + begin.length(), e = url.indexOf(end, offset);
        return e == -1 ? url.substring(offset) : url.substring(offset, e);
    }
}
