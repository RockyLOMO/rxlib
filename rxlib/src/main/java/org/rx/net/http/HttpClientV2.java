package org.rx.net.http;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.pool.AbstractChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.rx.io.Files;
import org.rx.io.HybridStream;
import org.rx.io.IOStream;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.rx.core.Extends.ifNull;
import static org.rx.core.Extends.tryClose;
import static org.rx.core.Sys.toJsonString;

@Slf4j
public class HttpClientV2 implements AutoCloseable {
    private static final AttributeKey<Exchange> EXCHANGE = AttributeKey.valueOf("rx.http.client.v2.exchange");
    private static final int BODY_CHUNK_SIZE = 8192;
    private static final long NO_EXPIRES = Long.MAX_VALUE;

    public static final class Request {
        @Getter
        private final HttpMethod method;
        @Getter
        private final String url;
        @Getter
        private final HttpHeaders headers = new DefaultHttpHeaders(false);
        @Getter
        private HttpClientBody body = HttpClientBody.EMPTY;
        private int timeoutMillis;
        private Proxy proxy;
        private Boolean enableCookie;

        Request(HttpMethod method, String url) {
            this.method = method;
            this.url = url;
        }

        public Request header(CharSequence name, Object value) {
            headers.set(name, value);
            return this;
        }

        public Request headers(HttpHeaders headers) {
            if (headers != null) {
                this.headers.setAll(headers);
            }
            return this;
        }

        public Request body(HttpClientBody body) {
            this.body = ifNull(body, HttpClientBody.EMPTY);
            return this;
        }

        public Request timeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Request proxy(Proxy proxy) {
            this.proxy = proxy;
            return this;
        }

        public Request enableCookie(boolean enableCookie) {
            this.enableCookie = enableCookie;
            return this;
        }
    }

    public static abstract class HttpClientBody implements AutoCloseable {
        public static final HttpClientBody EMPTY = new EmptyBody();

        final HttpHeaders headers = new DefaultHttpHeaders(false);

        public HttpHeaders headers() {
            return headers;
        }

        public abstract long contentLength();

        public boolean hasContent() {
            return contentLength() != 0L;
        }

        public abstract InputStream openStream();

        @Override
        public void close() {
        }

        public static HttpClientBody bytes(byte[] bytes, CharSequence contentType) {
            return new BytesBody(bytes, contentType);
        }

        public static HttpClientBody json(Object json) {
            byte[] bytes = toJsonString(json).getBytes(StandardCharsets.UTF_8);
            return bytes(bytes, "application/json; charset=UTF-8");
        }

        public static HttpClientBody form(Map<String, Object> forms) {
            String form = buildUrl(null, forms);
            if (!Strings.isEmpty(form)) {
                form = form.substring(1);
            }
            return bytes(form.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded;charset=UTF-8");
        }

        public static HttpClientBody multipart(Map<String, Object> forms, Map<String, IOStream> files) {
            return new MultipartBody(forms, files);
        }
    }

    static final class EmptyBody extends HttpClientBody {
        @Override
        public long contentLength() {
            return 0L;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
    }

    static class BytesBody extends HttpClientBody {
        final byte[] bytes;

        BytesBody(byte[] bytes, CharSequence contentType) {
            this.bytes = ifNull(bytes, new byte[0]);
            if (contentType != null) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
            }
        }

        @Override
        public long contentLength() {
            return bytes.length;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(bytes);
        }
    }

    static final class MultipartBody extends HttpClientBody {
        final String boundary;
        final HybridStream stream;
        final List<IOStream> files;

        @SneakyThrows
        MultipartBody(Map<String, Object> forms, Map<String, IOStream> files) {
            this.boundary = "----rxlib-" + Long.toHexString(System.nanoTime());
            this.files = files == null ? Collections.emptyList() : new ArrayList<>(files.values());
            this.stream = new HybridStream(Constants.MAX_HEAP_BUF_SIZE, false);
            headers.set(HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary);

            if (!MapUtils.isEmpty(forms)) {
                for (Map.Entry<String, Object> entry : forms.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    writeAscii("--" + boundary + "\r\n");
                    writeAscii("Content-Disposition: form-data; name=\"" + escape(entry.getKey()) + "\"\r\n\r\n");
                    stream.writeString(String.valueOf(entry.getValue()), StandardCharsets.UTF_8);
                    writeAscii("\r\n");
                }
            }
            if (!MapUtils.isEmpty(files)) {
                for (Map.Entry<String, IOStream> entry : files.entrySet()) {
                    IOStream file = entry.getValue();
                    if (file == null) {
                        continue;
                    }
                    String fileName = ifNull(file.getName(), entry.getKey());
                    writeAscii("--" + boundary + "\r\n");
                    writeAscii("Content-Disposition: form-data; name=\"" + escape(entry.getKey())
                            + "\"; filename=\"" + escape(fileName) + "\"\r\n");
                    writeAscii("Content-Type: " + Files.getMediaTypeFromName(fileName) + "\r\n\r\n");
                    if (file.canSeek()) {
                        file.rewind();
                    }
                    stream.write(file.getReader());
                    writeAscii("\r\n");
                }
            }
            writeAscii("--" + boundary + "--\r\n");
            stream.rewind();
        }

        void writeAscii(String value) {
            stream.write(value.getBytes(StandardCharsets.US_ASCII));
        }

        String escape(String value) {
            return value == null ? Strings.EMPTY : value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        @Override
        public long contentLength() {
            return stream.getLength();
        }

        @Override
        public synchronized InputStream openStream() {
            return stream.rewind().getReader();
        }

        @Override
        public void close() {
            tryClose(stream);
            tryClose(files);
        }
    }

    public static final class Response implements AutoCloseable {
        @Getter
        private final String requestUrl;
        @Getter
        private final int statusCode;
        @Getter
        private final HttpResponseStatus status;
        @Getter
        private final HttpHeaders headers;
        @Getter
        private final long elapsedNanos;
        @JSONField(serialize = false)
        private final HybridStream stream;
        private String text;
        private File file;

        Response(String requestUrl, HttpResponseStatus status, HttpHeaders headers, HybridStream stream, long elapsedNanos) {
            this.requestUrl = requestUrl;
            this.status = status;
            this.statusCode = status.code();
            this.headers = headers;
            this.stream = stream.rewind();
            this.elapsedNanos = elapsedNanos;
        }

        public InputStream responseStream() {
            return stream.rewind().getReader();
        }

        public Charset charset() {
            String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
            if (contentType == null) {
                return StandardCharsets.UTF_8;
            }
            String lower = contentType.toLowerCase(Locale.ENGLISH);
            int i = lower.indexOf("charset=");
            if (i == -1) {
                return StandardCharsets.UTF_8;
            }
            String charset = contentType.substring(i + 8).trim();
            int semicolon = charset.indexOf(';');
            if (semicolon != -1) {
                charset = charset.substring(0, semicolon).trim();
            }
            try {
                return Charset.forName(charset);
            } catch (Exception e) {
                return StandardCharsets.UTF_8;
            }
        }

        public synchronized HybridStream toStream() {
            return stream.rewind();
        }

        public synchronized File toFile(String filePath) {
            if (file == null) {
                Files.saveFile(filePath, responseStream());
                file = new File(filePath);
            }
            return file;
        }

        public <T extends Serializable> T toJson() {
            return (T) JSON.parse(toString());
        }

        @Override
        public synchronized String toString() {
            if (text == null) {
                text = IOStream.readString(responseStream(), charset());
            }
            return text;
        }

        @Override
        public void close() {
            tryClose(stream);
        }
    }

    public static final class Metrics {
        private final AtomicLong requests = new AtomicLong();
        private final AtomicLong success = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong timeout = new AtomicLong();
        private final AtomicLong uploadBytes = new AtomicLong();
        private final AtomicLong downloadBytes = new AtomicLong();
        private final AtomicLong totalLatencyNanos = new AtomicLong();
        private final AtomicLong maxLatencyNanos = new AtomicLong();

        public long requests() {
            return requests.get();
        }

        public long success() {
            return success.get();
        }

        public long failed() {
            return failed.get();
        }

        public long timeout() {
            return timeout.get();
        }

        public long uploadBytes() {
            return uploadBytes.get();
        }

        public long downloadBytes() {
            return downloadBytes.get();
        }

        public long totalLatencyNanos() {
            return totalLatencyNanos.get();
        }

        public long maxLatencyNanos() {
            return maxLatencyNanos.get();
        }

        public long usedDirectMemory() {
            return PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory();
        }

        void recordLatency(long elapsedNanos) {
            totalLatencyNanos.addAndGet(elapsedNanos);
            for (; ; ) {
                long old = maxLatencyNanos.get();
                if (elapsedNanos <= old || maxLatencyNanos.compareAndSet(old, elapsedNanos)) {
                    return;
                }
            }
        }
    }

    public static final class HttpClientCookie {
        final String name;
        final String value;
        final String domain;
        final String path;
        final long expiresAt;
        final boolean secure;
        final boolean httpOnly;
        final boolean hostOnly;

        HttpClientCookie(Cookie cookie, URI uri, long now) {
            name = cookie.name();
            value = cookie.value();
            String d = cookie.domain();
            hostOnly = Strings.isEmpty(d);
            domain = normalizeDomain(hostOnly ? uri.getHost() : d);
            path = Strings.isEmpty(cookie.path()) ? "/" : cookie.path();
            secure = cookie.isSecure();
            httpOnly = cookie.isHttpOnly();
            if (cookie.maxAge() == Cookie.UNDEFINED_MAX_AGE) {
                expiresAt = NO_EXPIRES;
            } else {
                expiresAt = cookie.maxAge() <= 0 ? 0L : now + TimeUnit.SECONDS.toMillis(cookie.maxAge());
            }
        }

        Cookie toNettyCookie() {
            io.netty.handler.codec.http.cookie.DefaultCookie cookie = new io.netty.handler.codec.http.cookie.DefaultCookie(name, value);
            cookie.setDomain(domain);
            cookie.setPath(path);
            cookie.setSecure(secure);
            cookie.setHttpOnly(httpOnly);
            return cookie;
        }

        boolean expired(long now) {
            return expiresAt < now;
        }

        boolean matches(URI uri, long now) {
            if (expired(now)) {
                return false;
            }
            if (secure && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = normalizeDomain(uri.getHost());
            if (hostOnly) {
                if (!Strings.hashEquals(domain, host)) {
                    return false;
                }
            } else if (!host.equals(domain) && !host.endsWith("." + domain)) {
                return false;
            }
            String requestPath = Strings.isEmpty(uri.getPath()) ? "/" : uri.getPath();
            return requestPath.startsWith(path);
        }

        static String normalizeDomain(String domain) {
            if (domain == null) {
                return Strings.EMPTY;
            }
            domain = domain.toLowerCase(Locale.ENGLISH);
            while (domain.startsWith(".")) {
                domain = domain.substring(1);
            }
            return domain;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof HttpClientCookie)) {
                return false;
            }
            HttpClientCookie that = (HttpClientCookie) o;
            return secure == that.secure && hostOnly == that.hostOnly
                    && name.equals(that.name) && domain.equals(that.domain) && path.equals(that.path);
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + domain.hashCode();
            result = 31 * result + path.hashCode();
            result = 31 * result + (secure ? 1 : 0);
            result = 31 * result + (hostOnly ? 1 : 0);
            return result;
        }
    }

    public static final class HttpClientCookieJar {
        final Set<HttpClientCookie> cookies = ConcurrentHashMap.newKeySet();

        String load(URI uri) {
            long now = System.currentTimeMillis();
            List<Cookie> matched = new ArrayList<>();
            for (Iterator<HttpClientCookie> it = cookies.iterator(); it.hasNext(); ) {
                HttpClientCookie cookie = it.next();
                if (cookie.expired(now)) {
                    it.remove();
                    continue;
                }
                if (cookie.matches(uri, now)) {
                    matched.add(cookie.toNettyCookie());
                }
            }
            return matched.isEmpty() ? null : ClientCookieEncoder.STRICT.encode(matched);
        }

        void save(URI uri, HttpHeaders headers) {
            long now = System.currentTimeMillis();
            List<String> setCookies = headers.getAll(HttpHeaderNames.SET_COOKIE);
            if (setCookies.isEmpty()) {
                return;
            }
            for (String raw : setCookies) {
                Cookie cookie = ClientCookieDecoder.STRICT.decode(raw);
                if (cookie == null) {
                    continue;
                }
                HttpClientCookie c = new HttpClientCookie(cookie, uri, now);
                cookies.remove(c);
                if (!c.expired(now)) {
                    cookies.add(c);
                }
            }
        }

        public void clearSession() {
            cookies.removeIf(p -> p.expiresAt == NO_EXPIRES);
        }

        public void clear() {
            cookies.clear();
        }
    }

    static final class PoolKey {
        final String scheme;
        final String host;
        final int port;
        final Proxy.Type proxyType;
        final String proxyAddress;

        PoolKey(URI uri, Proxy proxy) {
            scheme = uri.getScheme().toLowerCase(Locale.ENGLISH);
            host = uri.getHost();
            port = effectivePort(uri);
            if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
                proxyType = Proxy.Type.DIRECT;
                proxyAddress = Strings.EMPTY;
            } else {
                proxyType = proxy.type();
                proxyAddress = String.valueOf(proxy.address());
            }
        }

        boolean https() {
            return "https".equalsIgnoreCase(scheme);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PoolKey)) {
                return false;
            }
            PoolKey that = (PoolKey) o;
            return port == that.port && scheme.equals(that.scheme) && host.equals(that.host)
                    && proxyType == that.proxyType && proxyAddress.equals(that.proxyAddress);
        }

        @Override
        public int hashCode() {
            int result = scheme.hashCode();
            result = 31 * result + host.hashCode();
            result = 31 * result + port;
            result = 31 * result + proxyType.hashCode();
            result = 31 * result + proxyAddress.hashCode();
            return result;
        }
    }

    static final class Exchange {
        final FixedChannelPool pool;
        final Channel channel;
        final Request request;
        final CompletableFuture<Response> future;
        final Metrics metrics;
        final long startNanos;
        final HybridStream stream = new HybridStream(Constants.MAX_HEAP_BUF_SIZE, false);
        final HttpHeaders responseHeaders = new DefaultHttpHeaders(false);
        HttpResponseStatus status;
        boolean keepAlive = true;
        ScheduledFuture<?> timeoutFuture;
        boolean done;

        Exchange(FixedChannelPool pool, Channel channel, Request request, CompletableFuture<Response> future, Metrics metrics, long startNanos) {
            this.pool = pool;
            this.channel = channel;
            this.request = request;
            this.future = future;
            this.metrics = metrics;
            this.startNanos = startNanos;
        }

        void complete() {
            if (done) {
                return;
            }
            done = true;
            cancelTimeout();
            long elapsed = System.nanoTime() - startNanos;
            metrics.success.incrementAndGet();
            metrics.recordLatency(elapsed);
            Response response = new Response(request.url, ifNull(status, HttpResponseStatus.OK),
                    new DefaultHttpHeaders(false).set(responseHeaders), stream, elapsed);
            future.complete(response);
            releaseOrClose();
        }

        void fail(Throwable e) {
            if (done) {
                return;
            }
            done = true;
            cancelTimeout();
            metrics.failed.incrementAndGet();
            tryClose(stream);
            future.completeExceptionally(e);
            channel.attr(EXCHANGE).set(null);
            channel.close();
        }

        void timeout(int timeoutMillis) {
            metrics.timeout.incrementAndGet();
            fail(new TimeoutException("HTTP request timeout " + timeoutMillis + "ms: " + request.url));
        }

        void cancelTimeout() {
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        }

        void releaseOrClose() {
            channel.attr(EXCHANGE).set(null);
            if (keepAlive && channel.isActive()) {
                pool.release(channel);
            } else {
                channel.close();
            }
        }
    }

    final Map<PoolKey, FixedChannelPool> pools = new ConcurrentHashMap<>();
    final HttpHeaders defaultHeaders = new DefaultHttpHeaders(false);
    final HttpClientCookieJar cookieJar = new HttpClientCookieJar();
    final Metrics metrics = new Metrics();
    int connectTimeoutMillis;
    int readWriteTimeoutMillis;
    int maxConnectionsPerHost;
    int maxPendingAcquires;
    boolean enableCookie;
    Proxy proxy;
    volatile SslContext sslContext;

    public HttpClientV2() {
        RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
        connectTimeoutMillis = conf.getConnectTimeoutMillis();
        readWriteTimeoutMillis = conf.getReadWriteTimeoutMillis();
        maxConnectionsPerHost = Math.max(1, conf.getPoolMaxSize());
        maxPendingAcquires = Math.max(16, maxConnectionsPerHost * 4);
    }

    public static Request request(HttpMethod method, String url) {
        return new Request(method, url);
    }

    public Metrics metrics() {
        return metrics;
    }

    public HttpClientCookieJar cookieJar() {
        return cookieJar;
    }

    public HttpHeaders requestHeaders() {
        return defaultHeaders;
    }

    public HttpClientV2 withTimeoutMillis(int timeoutMillis) {
        return withTimeoutMillis(timeoutMillis, timeoutMillis);
    }

    public HttpClientV2 withTimeoutMillis(int connectTimeoutMillis, int readWriteTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readWriteTimeoutMillis = readWriteTimeoutMillis;
        return this;
    }

    public HttpClientV2 withMaxConnectionsPerHost(int maxConnectionsPerHost) {
        this.maxConnectionsPerHost = Math.max(1, maxConnectionsPerHost);
        return this;
    }

    public HttpClientV2 withProxy(Proxy proxy) {
        this.proxy = proxy;
        return this;
    }

    public HttpClientV2 withCookies(boolean enableCookie) {
        this.enableCookie = enableCookie;
        return this;
    }

    public HttpClientV2 withUserAgent() {
        defaultHeaders.set(HttpHeaderNames.USER_AGENT, RxConfig.INSTANCE.getNet().getUserAgent());
        return this;
    }

    public Response head(@NonNull String url) {
        return execute(request(HttpMethod.HEAD, url));
    }

    public Response get(@NonNull String url) {
        return execute(request(HttpMethod.GET, url));
    }

    public Response post(@NonNull String url, Map<String, Object> forms) {
        return post(url, forms, Collections.emptyMap());
    }

    public Response post(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        HttpClientBody body = MapUtils.isEmpty(files) ? HttpClientBody.form(forms) : HttpClientBody.multipart(forms, files);
        return execute(request(HttpMethod.POST, url).body(body));
    }

    public Response postJson(@NonNull String url, @NonNull Object json) {
        return execute(request(HttpMethod.POST, url).body(HttpClientBody.json(json)));
    }

    public Response put(@NonNull String url, Map<String, Object> forms) {
        return execute(request(HttpMethod.PUT, url).body(HttpClientBody.form(forms)));
    }

    public Response putJson(@NonNull String url, @NonNull Object json) {
        return execute(request(HttpMethod.PUT, url).body(HttpClientBody.json(json)));
    }

    public Response patch(@NonNull String url, Map<String, Object> forms) {
        return execute(request(HttpMethod.PATCH, url).body(HttpClientBody.form(forms)));
    }

    public Response patchJson(@NonNull String url, @NonNull Object json) {
        return execute(request(HttpMethod.PATCH, url).body(HttpClientBody.json(json)));
    }

    public Response delete(@NonNull String url, Map<String, Object> forms) {
        return execute(request(HttpMethod.DELETE, url).body(HttpClientBody.form(forms)));
    }

    public Response deleteJson(@NonNull String url, @NonNull Object json) {
        return execute(request(HttpMethod.DELETE, url).body(HttpClientBody.json(json)));
    }

    @SneakyThrows
    public Response execute(Request request) {
        return executeAsync(request).get();
    }

    public CompletableFuture<Response> executeAsync(Request request) {
        metrics.requests.incrementAndGet();
        CompletableFuture<Response> future = new CompletableFuture<>();
        URI uri;
        try {
            uri = URI.create(request.url);
            validateUri(uri);
        } catch (Throwable e) {
            metrics.failed.incrementAndGet();
            future.completeExceptionally(e);
            return future;
        }

        Proxy requestProxy = request.proxy != null ? request.proxy : proxy;
        PoolKey key = new PoolKey(uri, requestProxy);
        FixedChannelPool pool = pool(key, requestProxy);
        long startNanos = System.nanoTime();
        io.netty.util.concurrent.Future<Channel> acquireFuture = pool.acquire();
        acquireFuture.addListener(f -> {
            if (!f.isSuccess()) {
                metrics.failed.incrementAndGet();
                future.completeExceptionally(f.cause());
                return;
            }
            Channel channel = ((io.netty.util.concurrent.Future<Channel>) f).getNow();
            channel.eventLoop().execute(() -> send(pool, channel, uri, request, future, startNanos));
        });
        return future;
    }

    FixedChannelPool pool(PoolKey key, Proxy requestProxy) {
        return pools.computeIfAbsent(key, k -> {
            SocketConfig config = new SocketConfig();
            config.setConnectTimeoutMillis(connectTimeoutMillis);
            Bootstrap bootstrap = Sockets.bootstrap(config, ch -> {
            });
            bootstrap.remoteAddress(k.host, k.port);
            return new FixedChannelPool(bootstrap, new PoolHandler(k, requestProxy), maxConnectionsPerHost, maxPendingAcquires);
        });
    }

    void send(FixedChannelPool pool, Channel channel, URI uri, Request request, CompletableFuture<Response> future, long startNanos) {
        Exchange old = channel.attr(EXCHANGE).get();
        if (old != null) {
            old.fail(new IllegalStateException("Channel already has active HTTP exchange"));
        }
        Exchange exchange = new Exchange(pool, channel, request, future, metrics, startNanos);
        channel.attr(EXCHANGE).set(exchange);
        int timeoutMillis = request.timeoutMillis > 0 ? request.timeoutMillis : readWriteTimeoutMillis;
        exchange.timeoutFuture = channel.eventLoop().schedule(() -> exchange.timeout(timeoutMillis), timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            writeRequest(channel, uri, request, exchange);
        } catch (Throwable e) {
            exchange.fail(e);
        }
    }

    void writeRequest(Channel channel, URI uri, Request request, Exchange exchange) throws Exception {
        HttpClientBody body = ifNull(request.body, HttpClientBody.EMPTY);
        boolean hasBody = body.hasContent() || HttpMethod.POST.equals(request.method) || HttpMethod.PUT.equals(request.method)
                || HttpMethod.PATCH.equals(request.method) || HttpMethod.DELETE.equals(request.method);
        String requestUri = requestUri(uri);
        HttpHeaders headers = new DefaultHttpHeaders(false);
        headers.set(defaultHeaders);
        headers.set(request.headers);
        headers.set(body.headers());
        headers.set(HttpHeaderNames.HOST, hostHeader(uri));
        headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        headers.set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP + "," + HttpHeaderValues.DEFLATE);
        if (Boolean.TRUE.equals(request.enableCookie) || (request.enableCookie == null && enableCookie)) {
            String cookie = cookieJar.load(uri);
            if (!Strings.isEmpty(cookie)) {
                headers.set(HttpHeaderNames.COOKIE, cookie);
            }
        }

        if (!hasBody) {
            FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, request.method, requestUri);
            httpRequest.headers().set(headers);
            ChannelFuture writeFuture = channel.writeAndFlush(httpRequest);
            writeFuture.addListener(f -> {
                if (!f.isSuccess()) {
                    exchange.fail(f.cause());
                }
            });
            return;
        }

        long contentLength = body.contentLength();
        if (contentLength >= 0) {
            headers.set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        }
        if (contentLength == 0) {
            FullHttpRequest httpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, request.method, requestUri);
            httpRequest.headers().set(headers);
            ChannelFuture writeFuture = channel.writeAndFlush(httpRequest);
            writeFuture.addListener(f -> {
                tryClose(body);
                if (!f.isSuccess()) {
                    exchange.fail(f.cause());
                }
            });
            return;
        }

        HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, request.method, requestUri);
        httpRequest.headers().set(headers);
        if (contentLength < 0) {
            HttpUtil.setTransferEncodingChunked(httpRequest, true);
        }
        channel.write(httpRequest);
        InputStream in = body.openStream();
        metrics.uploadBytes.addAndGet(Math.max(0L, contentLength));
        ChannelFuture writeFuture = channel.writeAndFlush(new HttpChunkedInput(new ChunkedStream(in, BODY_CHUNK_SIZE)));
        writeFuture.addListener(f -> {
            tryClose(in);
            tryClose(body);
            if (!f.isSuccess()) {
                exchange.fail(f.cause());
            }
        });
    }

    static void validateUri(URI uri) {
        if (Strings.isEmpty(uri.getScheme()) || Strings.isEmpty(uri.getHost())) {
            throw new InvalidException("Invalid http url {}", uri);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new InvalidException("Unsupported http scheme {}", uri.getScheme());
        }
    }

    static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    static String requestUri(URI uri) {
        StringBuilder sb = new StringBuilder();
        sb.append(Strings.isEmpty(uri.getRawPath()) ? "/" : uri.getRawPath());
        if (!Strings.isEmpty(uri.getRawQuery())) {
            sb.append('?').append(uri.getRawQuery());
        }
        return sb.toString();
    }

    static String hostHeader(URI uri) {
        int port = effectivePort(uri);
        if (("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)) {
            return uri.getHost();
        }
        return uri.getHost() + ":" + port;
    }

    @SneakyThrows
    SslContext sslContext() {
        SslContext ctx = sslContext;
        if (ctx == null) {
            synchronized (this) {
                ctx = sslContext;
                if (ctx == null) {
                    sslContext = ctx = SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .build();
                }
            }
        }
        return ctx;
    }

    @Override
    public void close() {
        for (FixedChannelPool pool : pools.values()) {
            pool.close();
        }
        pools.clear();
    }

    final class PoolHandler extends AbstractChannelPoolHandler {
        final PoolKey key;
        final Proxy requestProxy;

        PoolHandler(PoolKey key, Proxy requestProxy) {
            this.key = key;
            this.requestProxy = requestProxy;
        }

        @Override
        public void channelCreated(Channel ch) throws Exception {
            if (requestProxy != null && requestProxy.type() == Proxy.Type.HTTP) {
                if (requestProxy instanceof AuthenticProxy
                        && !Strings.isEmpty(((AuthenticProxy) requestProxy).getUsername())) {
                    AuthenticProxy proxy = (AuthenticProxy) requestProxy;
                    ch.pipeline().addLast(new HttpProxyHandler(proxy.address(), proxy.getUsername(), proxy.getPassword()));
                } else {
                    ch.pipeline().addLast(new HttpProxyHandler(requestProxy.address()));
                }
            }
            if (key.https()) {
                ch.pipeline().addLast(sslContext().newHandler(ch.alloc(), key.host, key.port));
            }
            ch.pipeline().addLast(new HttpClientCodec(),
                    new HttpContentDecompressor(),
                    new ChunkedWriteHandler(),
                    new ClientHandler());
        }
    }

    final class ClientHandler extends SimpleChannelInboundHandler<HttpObject> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            Exchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange == null) {
                return;
            }
            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;
                if (response.status().code() < 200) {
                    return;
                }
                exchange.status = response.status();
                exchange.responseHeaders.set(response.headers());
                exchange.keepAlive = HttpUtil.isKeepAlive(response);
                if (Boolean.TRUE.equals(exchange.request.enableCookie) || (exchange.request.enableCookie == null && enableCookie)) {
                    cookieJar.save(URI.create(exchange.request.url), response.headers());
                }
            }
            if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                ByteBuf buf = content.content();
                int readableBytes = buf.readableBytes();
                if (readableBytes > 0) {
                    exchange.stream.write(buf, readableBytes);
                    metrics.downloadBytes.addAndGet(readableBytes);
                }
                if (msg instanceof LastHttpContent) {
                    exchange.complete();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Exchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange != null) {
                exchange.fail(cause);
            } else {
                ctx.close();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            Exchange exchange = ctx.channel().attr(EXCHANGE).get();
            if (exchange != null) {
                exchange.fail(new IllegalStateException("HTTP channel inactive: " + exchange.request.url));
            }
            super.channelInactive(ctx);
        }
    }

    public static String buildUrl(String url, Map<String, Object> queryString) {
        if (url == null) {
            url = Strings.EMPTY;
        }
        if (queryString == null) {
            return url;
        }

        Map<String, Object> query = new LinkedHashMap<>();
        query.putAll((Map) decodeQueryString(url));
        query.putAll(queryString);
        int i = url.indexOf("?");
        if (i != -1) {
            url = url.substring(0, i);
        }
        StringBuilder sb = new StringBuilder(url);
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            Object val = entry.getValue();
            if (val == null) {
                continue;
            }
            sb.append(sb.length() == url.length() ? "?" : "&").append(encodeUrl(entry.getKey())).append("=").append(encodeUrl(val.toString()));
        }
        return sb.toString();
    }

    @SneakyThrows
    public static Map<String, String> decodeQueryString(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        if (Strings.isEmpty(url)) {
            return params;
        }

        int i = url.indexOf("?");
        if (i != -1) {
            url = url.substring(i + 1);
        }
        String[] pairs = Strings.split(url, "&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()) : pair;
            String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name()) : null;
            params.put(key, value);
        }
        return params;
    }

    @SneakyThrows
    public static String encodeUrl(String str) {
        if (Strings.isEmpty(str)) {
            return Strings.EMPTY;
        }

        return URLEncoder.encode(str, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    @SneakyThrows
    public static String decodeUrl(String str) {
        if (Strings.isEmpty(str)) {
            return Strings.EMPTY;
        }

        return URLDecoder.decode(str, StandardCharsets.UTF_8.name()).replace("%20", "+");
    }
}
