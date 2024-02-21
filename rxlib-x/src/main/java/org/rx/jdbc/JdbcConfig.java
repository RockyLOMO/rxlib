package org.rx.jdbc;

import lombok.*;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;

@Data
@EqualsAndHashCode(callSuper = true)
public class JdbcConfig extends DataSourceConfig {
    private static final long serialVersionUID = 2890340670027176789L;

    private long connectionTimeoutMillis = 30000;
    private long idleTimeoutMillis = 60000;
    private long maxLifetimeMillis = 1800000;
    private int minPoolSize = 10;
    private int maxPoolSize = 10;
    private ConnectionPoolKind poolKind = ConnectionPoolKind.HikariCP;

    private String poolName;
    private boolean enableStreamingResults;
    private long executeTimeoutMillis = 30000;
    private boolean interruptTimeoutExecution = false;

    @Getter(AccessLevel.PRIVATE)
    @Setter(AccessLevel.PRIVATE)
    private InetSocketAddress endpoint;
    @Getter(AccessLevel.PRIVATE)
    @Setter(AccessLevel.PRIVATE)
    private String databaseName;

    public void setUrl(String url) {
        jdbcUrl = url;
        endpoint = null;
        databaseName = null;
    }

    public InetSocketAddress getEndpointFromUrl() {
        if (endpoint == null) {
            endpoint = getEndpointFromUrl(jdbcUrl);
        }
        return endpoint;
    }

    public String getDatabaseNameFromUrl() {
        if (databaseName == null) {
            databaseName = getDatabaseNameFromUrl(jdbcUrl);
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
