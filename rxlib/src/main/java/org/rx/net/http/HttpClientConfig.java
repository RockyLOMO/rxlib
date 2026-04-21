package org.rx.net.http;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.experimental.Accessors;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.core.Constants;
import org.rx.core.RxConfig;

import java.net.Proxy;

@Getter
@Accessors(chain = true)
public final class HttpClientConfig {
    private int connectTimeoutMillis;
    private int readWriteTimeoutMillis;
    private int acquireTimeoutMillis;
    private int maxConnectionsPerHost;
    private int maxPendingAcquires;
    private int responseOffloadThreshold;
    private int uploadFlushBytes;
    private int uploadFlushChunks;
    @Setter
    private boolean enableCookie;
    @Setter
    private boolean enableLog;
    @Setter
    private Proxy proxy;
    private SslContext sslContext;
    private HttpClientV2.HttpClientCookieJar cookieJar;

    public static HttpClientConfig defaults() {
        return new HttpClientConfig();
    }

    public HttpClientConfig() {
        RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
        connectTimeoutMillis = positive(conf.getConnectTimeoutMillis(), 30000);
        readWriteTimeoutMillis = positive(conf.getReadWriteTimeoutMillis(), connectTimeoutMillis);
        acquireTimeoutMillis = connectTimeoutMillis;
        maxConnectionsPerHost = Math.max(1, conf.getPoolMaxSize());
        maxPendingAcquires = Math.max(16, maxConnectionsPerHost << 2);
        responseOffloadThreshold = Constants.MAX_HEAP_BUF_SIZE;
        uploadFlushBytes = Constants.HEAP_BUF_SIZE << 4;
        uploadFlushChunks = 16;
        enableLog = conf.isEnableLog();
        cookieJar = HttpClientV2.COOKIES;
        sslContext = defaultSslContext();
    }

    public HttpClientConfig(HttpClientConfig source) {
        HttpClientConfig src = source != null ? source : defaults();
        connectTimeoutMillis = src.connectTimeoutMillis;
        readWriteTimeoutMillis = src.readWriteTimeoutMillis;
        acquireTimeoutMillis = src.acquireTimeoutMillis;
        maxConnectionsPerHost = src.maxConnectionsPerHost;
        maxPendingAcquires = src.maxPendingAcquires;
        responseOffloadThreshold = src.responseOffloadThreshold;
        uploadFlushBytes = src.uploadFlushBytes;
        uploadFlushChunks = src.uploadFlushChunks;
        enableCookie = src.enableCookie;
        enableLog = src.enableLog;
        proxy = src.proxy;
        sslContext = src.sslContext;
        cookieJar = src.cookieJar;
    }

    public HttpClientConfig copy() {
        return new HttpClientConfig(this);
    }

    public HttpClientConfig setTimeoutMillis(int timeoutMillis) {
        return setTimeoutMillis(timeoutMillis, timeoutMillis);
    }

    public HttpClientConfig setTimeoutMillis(int connectTimeoutMillis, int readWriteTimeoutMillis) {
        this.connectTimeoutMillis = positive(connectTimeoutMillis, this.connectTimeoutMillis);
        this.readWriteTimeoutMillis = positive(readWriteTimeoutMillis, this.readWriteTimeoutMillis);
        this.acquireTimeoutMillis = this.connectTimeoutMillis;
        return this;
    }

    public HttpClientConfig setAcquireTimeoutMillis(int acquireTimeoutMillis) {
        this.acquireTimeoutMillis = positive(acquireTimeoutMillis, this.acquireTimeoutMillis);
        return this;
    }

    public HttpClientConfig setMaxConnectionsPerHost(int maxConnectionsPerHost) {
        this.maxConnectionsPerHost = Math.max(1, maxConnectionsPerHost);
        this.maxPendingAcquires = Math.max(16, this.maxConnectionsPerHost << 2);
        return this;
    }

    public HttpClientConfig setMaxPendingAcquires(int maxPendingAcquires) {
        this.maxPendingAcquires = Math.max(1, maxPendingAcquires);
        return this;
    }

    public HttpClientConfig setPendingAcquireMaxCount(int pendingAcquireMaxCount) {
        return setMaxPendingAcquires(pendingAcquireMaxCount);
    }

    public int getPendingAcquireMaxCount() {
        return maxPendingAcquires;
    }

    public HttpClientConfig setPool(int maxConnectionsPerHost, int pendingAcquireMaxCount, int acquireTimeoutMillis) {
        return setMaxConnectionsPerHost(maxConnectionsPerHost)
                .setMaxPendingAcquires(pendingAcquireMaxCount)
                .setAcquireTimeoutMillis(acquireTimeoutMillis);
    }

    public HttpClientConfig setResponseOffloadThreshold(int responseOffloadThreshold) {
        this.responseOffloadThreshold = Math.max(0, responseOffloadThreshold);
        return this;
    }

    public HttpClientConfig setUploadFlushBytes(int uploadFlushBytes) {
        this.uploadFlushBytes = Math.max(1, uploadFlushBytes);
        return this;
    }

    public HttpClientConfig setUploadFlushChunks(int uploadFlushChunks) {
        this.uploadFlushChunks = Math.max(1, uploadFlushChunks);
        return this;
    }

    public HttpClientConfig setSslContext(SslContext sslContext) {
        this.sslContext = sslContext != null ? sslContext : defaultSslContext();
        return this;
    }

    public HttpClientConfig setCookieJar(HttpClientV2.HttpClientCookieJar cookieJar) {
        this.cookieJar = cookieJar != null ? cookieJar : HttpClientV2.COOKIES;
        return this;
    }

    @SneakyThrows
    private static SslContext defaultSslContext() {
        return SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
