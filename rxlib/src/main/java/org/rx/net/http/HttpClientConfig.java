package org.rx.net.http;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.Getter;
import lombok.SneakyThrows;
import org.rx.core.Constants;
import org.rx.core.RxConfig;

import java.net.Proxy;

@Getter
public final class HttpClientConfig {
    private int connectTimeoutMillis;
    private int readWriteTimeoutMillis;
    private int acquireTimeoutMillis;
    private int maxConnectionsPerHost;
    private int maxPendingAcquires;
    private int responseOffloadThreshold;
    private int uploadFlushBytes;
    private int uploadFlushChunks;
    private boolean enableCookie;
    private boolean enableLog;
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

    public HttpClientConfig withFeatures(boolean enableCookie, boolean enableLog) {
        this.enableCookie = enableCookie;
        this.enableLog = enableLog;
        return this;
    }

    public HttpClientConfig withCookies(boolean enableCookie) {
        this.enableCookie = enableCookie;
        return this;
    }

    public HttpClientConfig withTimeoutMillis(int timeoutMillis) {
        return withTimeoutMillis(timeoutMillis, timeoutMillis);
    }

    public HttpClientConfig withTimeoutMillis(int connectTimeoutMillis, int readWriteTimeoutMillis) {
        this.connectTimeoutMillis = positive(connectTimeoutMillis, this.connectTimeoutMillis);
        this.readWriteTimeoutMillis = positive(readWriteTimeoutMillis, this.readWriteTimeoutMillis);
        acquireTimeoutMillis = this.connectTimeoutMillis;
        return this;
    }

    public HttpClientConfig withAcquireTimeoutMillis(int acquireTimeoutMillis) {
        this.acquireTimeoutMillis = positive(acquireTimeoutMillis, this.acquireTimeoutMillis);
        return this;
    }

    public HttpClientConfig withMaxConnectionsPerHost(int maxConnectionsPerHost) {
        this.maxConnectionsPerHost = Math.max(1, maxConnectionsPerHost);
        maxPendingAcquires = Math.max(16, this.maxConnectionsPerHost << 2);
        return this;
    }

    public HttpClientConfig withMaxPendingAcquires(int maxPendingAcquires) {
        this.maxPendingAcquires = Math.max(1, maxPendingAcquires);
        return this;
    }

    public HttpClientConfig withPendingAcquireMaxCount(int pendingAcquireMaxCount) {
        return withMaxPendingAcquires(pendingAcquireMaxCount);
    }

    public int getPendingAcquireMaxCount() {
        return maxPendingAcquires;
    }

    public HttpClientConfig withPool(int maxConnectionsPerHost, int pendingAcquireMaxCount, int acquireTimeoutMillis) {
        return withMaxConnectionsPerHost(maxConnectionsPerHost)
                .withMaxPendingAcquires(pendingAcquireMaxCount)
                .withAcquireTimeoutMillis(acquireTimeoutMillis);
    }

    public HttpClientConfig withResponseOffloadThreshold(int responseOffloadThreshold) {
        this.responseOffloadThreshold = Math.max(0, responseOffloadThreshold);
        return this;
    }

    public HttpClientConfig withUploadFlushBytes(int uploadFlushBytes) {
        this.uploadFlushBytes = Math.max(1, uploadFlushBytes);
        return this;
    }

    public HttpClientConfig withUploadFlushChunks(int uploadFlushChunks) {
        this.uploadFlushChunks = Math.max(1, uploadFlushChunks);
        return this;
    }

    public HttpClientConfig withProxy(Proxy proxy) {
        this.proxy = proxy;
        return this;
    }

    public HttpClientConfig withSslContext(SslContext sslContext) {
        this.sslContext = sslContext != null ? sslContext : defaultSslContext();
        return this;
    }

    public HttpClientConfig withCookieJar(HttpClientV2.HttpClientCookieJar cookieJar) {
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
