package org.rx.net.http;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.pool.AbstractChannelPoolHandler;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.rx.annotation.DbColumn;
import org.rx.bean.Tuple;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;
import org.rx.io.Files;
import org.rx.io.HybridStream;
import org.rx.io.IOStream;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.util.function.BiFunc;

import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class HttpClientV2 implements AutoCloseable {
    public static final String FORM_TYPE = "application/x-www-form-urlencoded;charset=UTF-8";
    public static final String JSON_TYPE = "application/json; charset=UTF-8";
    static final AttributeKey<RequestState> STATE_KEY = AttributeKey.valueOf("httpClientV2State");
    static final HttpClientCookieJar COOKIES = new HttpClientCookieJar();
    static final int RESPONSE_OFFLOAD_THRESHOLD = Constants.MAX_HEAP_BUF_SIZE;
    static final int UPLOAD_FLUSH_BYTES = Constants.HEAP_BUF_SIZE << 4;
    static final int UPLOAD_FLUSH_CHUNKS = 16;

    final Map<PoolKey, FixedChannelPool> pools = new ConcurrentHashMap<>();
    final SslContext sslContext;
    final HttpClientCookieJar cookieJar;
    final Metrics metrics = new Metrics();
    volatile int connectTimeoutMillis;
    volatile int readWriteTimeoutMillis;
    volatile int maxConnectionsPerHost;
    volatile int maxPendingAcquires;
    volatile boolean enableCookie;
    volatile boolean enableLog;
    volatile Proxy proxy;
    HttpHeaders reqHeaders;

    public HttpClientV2() {
        this(COOKIES);
    }

    @SneakyThrows
    public HttpClientV2(HttpClientCookieJar cookieJar) {
        this.cookieJar = cookieJar != null ? cookieJar : COOKIES;
        sslContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        RxConfig.NetConfig conf = RxConfig.INSTANCE.getNet();
        withFeatures(false, conf.isEnableLog());
        withTimeoutMillis(conf.getConnectTimeoutMillis(), conf.getReadWriteTimeoutMillis());
        maxConnectionsPerHost = Math.max(1, conf.getPoolMaxSize());
        maxPendingAcquires = Math.max(16, maxConnectionsPerHost << 2);
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

    public HttpClientV2 withFeatures(boolean enableCookie, boolean enableLog) {
        this.enableCookie = enableCookie;
        this.enableLog = enableLog;
        return this;
    }

    public HttpClientV2 withCookies(boolean enableCookie) {
        this.enableCookie = enableCookie;
        return this;
    }

    public HttpClientV2 withTimeoutMillis(int timeoutMillis) {
        return withTimeoutMillis(timeoutMillis, timeoutMillis);
    }

    public HttpClientV2 withTimeoutMillis(int connectTimeoutMillis, int readWriteTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readWriteTimeoutMillis = readWriteTimeoutMillis;
        close();
        return this;
    }

    public synchronized HttpClientV2 withMaxConnectionsPerHost(int maxConnectionsPerHost) {
        this.maxConnectionsPerHost = Math.max(1, maxConnectionsPerHost);
        this.maxPendingAcquires = Math.max(16, this.maxConnectionsPerHost << 2);
        close();
        return this;
    }

    public synchronized HttpClientV2 withProxy(Proxy proxy) {
        this.proxy = proxy;
        close();
        return this;
    }

    public HttpClientV2 withProxy(AuthenticProxy proxy) {
        return withProxy((Proxy) proxy);
    }

    public HttpClientV2 withUserAgent() {
        requestHeaders().set(HttpHeaderNames.USER_AGENT, RxConfig.INSTANCE.getNet().getUserAgent());
        return this;
    }

    public HttpClientV2 withRequestCookie(String rawCookie) {
        requestHeaders().set(HttpHeaderNames.COOKIE, rawCookie);
        return this;
    }

    public synchronized HttpHeaders requestHeaders() {
        if (reqHeaders == null) {
            reqHeaders = new DefaultHttpHeaders();
        }
        return reqHeaders;
    }

    synchronized HttpHeaders requestHeadersSnapshot() {
        HttpHeaders headers = new DefaultHttpHeaders();
        if (reqHeaders != null) {
            headers.set(reqHeaders);
        }
        return headers;
    }

    @Override
    public void close() {
        for (FixedChannelPool pool : pools.values()) {
            pool.close();
        }
        pools.clear();
    }

    public static final class Request {
        @Getter
        private final HttpMethod method;
        @Getter
        private final String url;
        @Getter
        private final HttpHeaders headers = new DefaultHttpHeaders(false);
        @Getter
        private RequestContent body = EmptyContent.INSTANCE;
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
                this.headers.set(headers);
            }
            return this;
        }

        public Request body(RequestContent body) {
            this.body = body != null ? body : EmptyContent.INSTANCE;
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

    public interface RequestContent extends AutoCloseable {
        String contentType();

        boolean streaming();

        ByteBuf toFullContent(Channel channel);

        void writeStreaming(Channel channel, RequestState state) throws Exception;

        @Override
        default void close() {
        }
    }

    public static final class HttpClientBody {
        private HttpClientBody() {
        }

        public static RequestContent bytes(byte[] bytes, CharSequence contentType) {
            return new BytesContent(contentType == null ? null : contentType.toString(), bytes);
        }

        public static RequestContent json(Object json) {
            return new JsonContent(json);
        }

        public static RequestContent form(Map<String, Object> forms) {
            return new FormContent(forms);
        }

        public static RequestContent multipart(Map<String, Object> forms, Map<String, IOStream> files) {
            return new MultipartContent(forms, files);
        }
    }

    static final class EmptyContent implements RequestContent {
        static final EmptyContent INSTANCE = new EmptyContent();

        @Override
        public String contentType() {
            return null;
        }

        @Override
        public boolean streaming() {
            return false;
        }

        @Override
        public ByteBuf toFullContent(Channel channel) {
            return channel.alloc().buffer(0, 0);
        }

        @Override
        public void writeStreaming(Channel channel, RequestState state) {
        }
    }

    static class BytesContent implements RequestContent {
        final String contentType;
        final byte[] data;

        BytesContent(String contentType, byte[] data) {
            this.contentType = contentType;
            this.data = data != null ? data : new byte[0];
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public boolean streaming() {
            return false;
        }

        @Override
        public ByteBuf toFullContent(Channel channel) {
            ByteBuf buf = channel.alloc().buffer(data.length, data.length);
            buf.writeBytes(data);
            return buf;
        }

        @Override
        public void writeStreaming(Channel channel, RequestState state) {
        }
    }

    static final class StreamContent implements RequestContent {
        final String contentType;
        final InputStream in;

        StreamContent(String contentType, InputStream in) {
            this.contentType = contentType;
            this.in = in;
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public boolean streaming() {
            return true;
        }

        @Override
        public ByteBuf toFullContent(Channel channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeStreaming(Channel channel, RequestState state) throws Exception {
            UploadWriter writer = new UploadWriter(channel, state);
            byte[] buffer = new byte[Constants.HEAP_BUF_SIZE];
            int read;
            while ((read = in.read(buffer)) != Constants.IO_EOF) {
                if (!channel.isActive()) {
                    throw new IllegalStateException("Channel closed");
                }
                ByteBuf buf = channel.alloc().buffer(read, read);
                buf.writeBytes(buffer, 0, read);
                writer.write(buf, read);
            }
            writer.finish();
        }
    }

    public static final class JsonContent extends BytesContent {
        @Getter
        final Object json;

        JsonContent(Object json) {
            super(JSON_TYPE, Sys.toJsonString(json).getBytes(StandardCharsets.UTF_8));
            this.json = json;
        }
    }

    public static final class FormContent extends BytesContent {
        FormContent(Map<String, Object> forms) {
            super(FORM_TYPE, formBytes(forms));
        }

        private static byte[] formBytes(Map<String, Object> forms) {
            String formString = buildUrl(null, forms);
            if (!Strings.isEmpty(formString)) {
                formString = formString.substring(1);
            }
            return formString.getBytes(StandardCharsets.UTF_8);
        }
    }

    public static final class MultipartContent implements RequestContent {
        static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

        final Map<String, Object> forms;
        final Map<String, IOStream> files;
        final String boundary;
        final String contentType;

        MultipartContent(Map<String, Object> forms, Map<String, IOStream> files) {
            this.forms = forms != null ? forms : Collections.emptyMap();
            this.files = files != null ? files : Collections.emptyMap();
            boundary = "----RxNettyBoundary" + Long.toHexString(System.nanoTime()) + Long.toHexString(ThreadLocalRandom.current().nextLong());
            contentType = "multipart/form-data; boundary=" + boundary;
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public boolean streaming() {
            return true;
        }

        @Override
        public ByteBuf toFullContent(Channel channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeStreaming(Channel channel, RequestState state) throws Exception {
            UploadWriter writer = new UploadWriter(channel, state);
            for (Map.Entry<String, Object> entry : forms.entrySet()) {
                Object value = entry.getValue();
                if (value == null) {
                    continue;
                }
                writeAscii(writer, partPrefix(entry.getKey(), null, null));
                writeAscii(writer, value.toString());
                writeBytes(writer, CRLF);
            }
            for (Map.Entry<String, IOStream> entry : files.entrySet()) {
                IOStream stream = entry.getValue();
                if (stream == null) {
                    continue;
                }
                String fileName = Strings.isEmpty(stream.getName()) ? entry.getKey() : stream.getName();
                writeAscii(writer, partPrefix(entry.getKey(), fileName, Files.getMediaTypeFromName(fileName)));
                writeStream(writer, stream.rewind().getReader());
                writeBytes(writer, CRLF);
            }
            writeAscii(writer, "--" + boundary + "--\r\n");
            writer.finish();
        }

        private String partPrefix(String name, String fileName, String mediaType) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(escapeHeader(name)).append("\"");
            if (fileName != null) {
                sb.append("; filename=\"").append(escapeHeader(fileName)).append("\"");
            }
            sb.append("\r\n");
            if (mediaType != null) {
                sb.append("Content-Type: ").append(mediaType).append("\r\n");
            }
            sb.append("\r\n");
            return sb.toString();
        }

        private String escapeHeader(String value) {
            return value == null ? Strings.EMPTY : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", Strings.EMPTY).replace("\n", Strings.EMPTY);
        }

        private void writeAscii(UploadWriter writer, String value) throws Exception {
            writeBytes(writer, value.getBytes(StandardCharsets.UTF_8));
        }

        private void writeBytes(UploadWriter writer, byte[] bytes) throws Exception {
            ByteBuf buf = writer.channel.alloc().buffer(bytes.length, bytes.length);
            buf.writeBytes(bytes);
            writer.write(buf, bytes.length);
        }

        private void writeStream(UploadWriter writer, InputStream in) throws Exception {
            byte[] buffer = new byte[Constants.HEAP_BUF_SIZE];
            int read;
            while ((read = in.read(buffer)) != Constants.IO_EOF) {
                if (!writer.channel.isActive()) {
                    throw new IllegalStateException("Channel closed");
                }
                ByteBuf buf = writer.channel.alloc().buffer(read, read);
                buf.writeBytes(buffer, 0, read);
                writer.write(buf, read);
            }
        }

        @Override
        public void close() {
            tryClose(files.values());
        }
    }

    static final class UploadWriter {
        final Channel channel;
        final RequestState state;
        int pendingBytes;
        int pendingChunks;
        ChannelFuture lastFuture;

        UploadWriter(Channel channel, RequestState state) {
            this.channel = channel;
            this.state = state;
        }

        void write(ByteBuf buf, int bytes) throws Exception {
            if (!channel.isActive()) {
                ReferenceCountUtil.release(buf);
                throw new IllegalStateException("Channel closed");
            }
            try {
                lastFuture = channel.write(new DefaultHttpContent(buf));
            } catch (Throwable e) {
                ReferenceCountUtil.release(buf);
                throw e;
            }
            state.metrics.uploadBytes.addAndGet(bytes);
            pendingBytes += bytes;
            pendingChunks++;
            if (pendingBytes >= UPLOAD_FLUSH_BYTES || pendingChunks >= UPLOAD_FLUSH_CHUNKS || !channel.isWritable()) {
                flush();
            }
        }

        void flush() throws Exception {
            if (pendingChunks == 0) {
                return;
            }
            channel.flush();
            awaitWrite(lastFuture, state.readWriteTimeoutMillis);
            pendingBytes = 0;
            pendingChunks = 0;
        }

        void finish() throws Exception {
            ChannelFuture future = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            pendingBytes = 0;
            pendingChunks = 0;
            awaitWrite(future, state.readWriteTimeoutMillis);
        }
    }

    static final class PoolKey {
        final String scheme;
        final String host;
        final int port;

        final Proxy.Type proxyType;
        final SocketAddress proxyAddress;
        final String proxyUsername;
        final String proxyPassword;

        PoolKey(URI uri, Proxy proxy) {
            scheme = uri.getScheme().toLowerCase(Locale.ENGLISH);
            host = uri.getHost().toLowerCase(Locale.ENGLISH);
            int p = uri.getPort();
            port = p > 0 ? p : (isHttps() ? 443 : 80);
            if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
                proxyType = Proxy.Type.DIRECT;
                proxyAddress = null;
                proxyUsername = null;
                proxyPassword = null;
            } else {
                proxyType = proxy.type();
                proxyAddress = proxy.address();
                if (proxy instanceof AuthenticProxy) {
                    proxyUsername = ((AuthenticProxy) proxy).getUsername();
                    proxyPassword = ((AuthenticProxy) proxy).getPassword();
                } else {
                    proxyUsername = null;
                    proxyPassword = null;
                }
            }
        }

        boolean isHttps() {
            return "https".equals(scheme);
        }

        InetSocketAddress unresolvedAddress() {
            return InetSocketAddress.createUnresolved(host, port);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PoolKey)) {
                return false;
            }
            PoolKey poolKey = (PoolKey) o;
            return port == poolKey.port && scheme.equals(poolKey.scheme) && host.equals(poolKey.host)
                    && proxyType == poolKey.proxyType
                    && Objects.equals(proxyAddress, poolKey.proxyAddress)
                    && Objects.equals(proxyUsername, poolKey.proxyUsername)
                    && Objects.equals(proxyPassword, poolKey.proxyPassword);
        }

        @Override
        public int hashCode() {
            int result = scheme.hashCode();
            result = 31 * result + host.hashCode();
            result = 31 * result + port;
            result = 31 * result + proxyType.hashCode();
            result = 31 * result + (proxyAddress != null ? proxyAddress.hashCode() : 0);
            result = 31 * result + (proxyUsername != null ? proxyUsername.hashCode() : 0);
            result = 31 * result + (proxyPassword != null ? proxyPassword.hashCode() : 0);
            return result;
        }
    }

    static final class RequestState {
        final URI uri;
        final PoolKey key;
        final FixedChannelPool pool;
        final CompletableFuture<ResponseContent> future;
        final int readWriteTimeoutMillis;
        final HttpHeaders requestHeaders;
        final RequestContent content;
        final Metrics metrics;
        final long startNanos;
        final boolean cookieEnabled;
        final boolean logEnabled;
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean contentClosed = new AtomicBoolean();
        final Object responseWriteLock = new Object();
        CompletableFuture<Void> responseWriteTail = CompletableFuture.completedFuture(null);
        HttpResponse response;
        HybridStream stream;
        ScheduledFuture<?> timeoutFuture;
        long responseBytes;
        boolean responseOffloaded;

        RequestState(URI uri, PoolKey key, FixedChannelPool pool, CompletableFuture<ResponseContent> future, int readWriteTimeoutMillis,
                     HttpHeaders requestHeaders, RequestContent content, Metrics metrics, long startNanos, boolean cookieEnabled, boolean logEnabled) {
            this.uri = uri;
            this.key = key;
            this.pool = pool;
            this.future = future;
            this.readWriteTimeoutMillis = readWriteTimeoutMillis;
            this.requestHeaders = requestHeaders;
            this.content = content;
            this.metrics = metrics;
            this.startNanos = startNanos;
            this.cookieEnabled = cookieEnabled;
            this.logEnabled = logEnabled;
        }

        void closeContent() {
            if (contentClosed.compareAndSet(false, true)) {
                tryClose(content);
            }
        }

        void cancelTimeout() {
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        }

        CompletableFuture<Void> appendResponseContent(ByteBuf src, int readableBytes) {
            ByteBuf retained = src.retainedSlice(src.readerIndex(), readableBytes);
            synchronized (responseWriteLock) {
                responseWriteTail = responseWriteTail.thenRunAsync(() -> {
                    try {
                        stream.write(retained, retained.readableBytes());
                        metrics.downloadBytes.addAndGet(readableBytes);
                    } finally {
                        ReferenceCountUtil.release(retained);
                    }
                }, Tasks.executor());
                return responseWriteTail;
            }
        }

        CompletableFuture<Void> responseWriteTail() {
            synchronized (responseWriteLock) {
                return responseWriteTail;
            }
        }
    }

    final class ClientInboundHandler extends SimpleChannelInboundHandler<HttpObject> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            RequestState state = ctx.channel().attr(STATE_KEY).get();
            if (state == null) {
                return;
            }

            if (msg instanceof HttpResponse) {
                state.response = (HttpResponse) msg;
                Long len = parseLong(state.response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
                state.stream = new HybridStream(len != null && len > Constants.MAX_HEAP_BUF_SIZE ? HybridStream.NON_MEMORY_SIZE : Constants.MAX_HEAP_BUF_SIZE, false);
            }

            if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                if (state.stream == null) {
                    state.stream = new HybridStream(Constants.MAX_HEAP_BUF_SIZE, false);
                }
                int readableBytes = content.content().readableBytes();
                if (readableBytes > 0) {
                    boolean offload = state.responseOffloaded || state.responseBytes + readableBytes > RESPONSE_OFFLOAD_THRESHOLD;
                    state.responseBytes += readableBytes;
                    if (offload) {
                        state.responseOffloaded = true;
                        ctx.channel().config().setAutoRead(false);
                        CompletableFuture<Void> writeFuture = state.appendResponseContent(content.content(), readableBytes);
                        boolean last = msg instanceof LastHttpContent;
                        writeFuture.whenComplete((v, e) -> ctx.channel().eventLoop().execute(() -> {
                            if (e != null) {
                                fail(ctx.channel(), e);
                                return;
                            }
                            if (last) {
                                complete(ctx.channel(), state);
                                return;
                            }
                            if (!state.completed.get() && ctx.channel().isActive()) {
                                ctx.channel().config().setAutoRead(true);
                                ctx.channel().read();
                            }
                        }));
                        return;
                    }
                    state.stream.write(content.content(), readableBytes);
                    state.metrics.downloadBytes.addAndGet(readableBytes);
                }
                if (msg instanceof LastHttpContent) {
                    completeAfterWrites(ctx.channel(), state);
                }
            }
        }

        private void completeAfterWrites(Channel channel, RequestState state) {
            CompletableFuture<Void> writeTail = state.responseWriteTail();
            if (writeTail.isDone()) {
                if (writeTail.isCompletedExceptionally()) {
                    writeTail.whenComplete((v, e) -> fail(channel, e));
                } else {
                    complete(channel, state);
                }
                return;
            }
            writeTail.whenComplete((v, e) -> channel.eventLoop().execute(() -> {
                if (e != null) {
                    fail(channel, e);
                } else {
                    complete(channel, state);
                }
            }));
        }

        private Long parseLong(String value) {
            if (Strings.isEmpty(value)) {
                return null;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private void complete(Channel channel, RequestState state) {
            if (!state.completed.compareAndSet(false, true)) {
                return;
            }
            channel.attr(STATE_KEY).set(null);
            state.cancelTimeout();
            state.closeContent();
            HttpResponse response = state.response;
            boolean keepAlive = response != null && HttpUtil.isKeepAlive(response) && channel.isActive();
            long elapsedNanos = System.nanoTime() - state.startNanos;
            ResponseContent content = new ResponseContent(state.uri.toString(), response, state.stream, elapsedNanos);
            state.metrics.success.incrementAndGet();
            state.metrics.recordLatency(elapsedNanos);
            if (channel.isActive()) {
                channel.config().setAutoRead(true);
            }
            release(channel, state, keepAlive);
            if (state.cookieEnabled) {
                Tasks.run(() -> completeFuture(state, content));
            } else {
                completeFuture(state, content);
            }
        }

        private void completeFuture(RequestState state, ResponseContent content) {
            if (state.cookieEnabled) {
                cookieJar.saveFromResponse(state.uri, content.headers.getAll(HttpHeaderNames.SET_COOKIE));
            }
            if (state.logEnabled) {
                log.info("{} -> {}", state.uri, content.getStatusCode());
            }
            state.future.complete(content);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            fail(ctx.channel(), new IllegalStateException("HTTP channel closed"));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            fail(ctx.channel(), cause);
        }
    }

    @Getter
    public static class Response implements AutoCloseable {
        final String responseUrl;
        final String requestUrl;
        @JSONField(serialize = false)
        final HttpResponse response;
        final HttpResponseStatus status;
        final int statusCode;
        final HttpHeaders headers;
        final HybridStream stream;
        final long elapsedNanos;
        String str;
        File file;

        Response(String responseUrl, HttpResponse response, HybridStream stream, long elapsedNanos) {
            this.responseUrl = responseUrl;
            this.requestUrl = responseUrl;
            this.response = response;
            status = response != null ? response.status() : HttpResponseStatus.OK;
            statusCode = status.code();
            headers = new DefaultHttpHeaders(false);
            if (response != null) {
                headers.set(response.headers());
            }
            this.stream = stream != null ? stream.rewind() : new HybridStream(Constants.MAX_HEAP_BUF_SIZE, false);
            this.elapsedNanos = elapsedNanos;
        }

        public HttpHeaders responseHeaders() {
            return headers;
        }

        @JSONField(serialize = false)
        public Charset getCharset() {
            String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
            if (Strings.isEmpty(contentType)) {
                return StandardCharsets.UTF_8;
            }
            String[] parts = contentType.split(";");
            for (String part : parts) {
                String p = part.trim();
                if (Strings.startsWithIgnoreCase(p, "charset=")) {
                    try {
                        return Charset.forName(p.substring("charset=".length()));
                    } catch (Exception e) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
            return StandardCharsets.UTF_8;
        }

        public synchronized InputStream responseStream() {
            return stream.rewind().getReader();
        }

        public synchronized HybridStream toStream() {
            return stream.rewind();
        }

        @SneakyThrows
        public synchronized File toFile(String filePath) {
            if (file == null) {
                Files.saveFile(filePath, stream.rewind().getReader());
                file = new File(filePath);
            }
            return file;
        }

        public <T extends Serializable> T toJson() {
            return (T) JSON.parse(toString());
        }

        @SneakyThrows
        public synchronized <T> T handle(BiFunc<InputStream, T> fn) {
            return fn.invoke(stream.rewind().getReader());
        }

        @Override
        public synchronized String toString() {
            if (str == null) {
                str = IOStream.readString(stream.rewind().getReader(), getCharset());
            }
            return str;
        }

        @Override
        public void close() {
            tryClose(stream);
        }
    }

    public static final class ResponseContent extends Response {
        ResponseContent(String responseUrl, HttpResponse response, HybridStream stream, long elapsedNanos) {
            super(responseUrl, response, stream, elapsedNanos);
        }

        public String getResponseText() {
            return toString();
        }

        @SneakyThrows
        public synchronized <T> T handle(BiFunc<InputStream, T> fn) {
            return fn.invoke(stream.rewind().getReader());
        }
    }

    public ResponseContent head(@NonNull String url) {
        return invoke(url, HttpMethod.HEAD, EmptyContent.INSTANCE);
    }

    public ResponseContent get(@NonNull String url) {
        return invoke(url, HttpMethod.GET, EmptyContent.INSTANCE);
    }

    public ResponseContent post(String url, Map<String, Object> forms) {
        return post(url, forms, Collections.emptyMap());
    }

    public ResponseContent post(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        if (MapUtils.isEmpty(files)) {
            return invoke(url, HttpMethod.POST, new FormContent(forms));
        }
        return invoke(url, HttpMethod.POST, new MultipartContent(forms, files));
    }

    public ResponseContent postJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.POST, new JsonContent(json));
    }

    public ResponseContent put(String url, Map<String, Object> forms) {
        return put(url, forms, Collections.emptyMap());
    }

    public ResponseContent put(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        if (MapUtils.isEmpty(files)) {
            return invoke(url, HttpMethod.PUT, new FormContent(forms));
        }
        return invoke(url, HttpMethod.PUT, new MultipartContent(forms, files));
    }

    public ResponseContent putJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.PUT, new JsonContent(json));
    }

    public ResponseContent patch(String url, Map<String, Object> forms) {
        return patch(url, forms, Collections.emptyMap());
    }

    public ResponseContent patch(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        if (MapUtils.isEmpty(files)) {
            return invoke(url, HttpMethod.PATCH, new FormContent(forms));
        }
        return invoke(url, HttpMethod.PATCH, new MultipartContent(forms, files));
    }

    public ResponseContent patchJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.PATCH, new JsonContent(json));
    }

    public ResponseContent delete(String url, Map<String, Object> forms) {
        return delete(url, forms, Collections.emptyMap());
    }

    public ResponseContent delete(@NonNull String url, Map<String, Object> forms, Map<String, IOStream> files) {
        if (MapUtils.isEmpty(files)) {
            return invoke(url, HttpMethod.DELETE, new FormContent(forms));
        }
        return invoke(url, HttpMethod.DELETE, new MultipartContent(forms, files));
    }

    public ResponseContent deleteJson(@NonNull String url, @NonNull Object json) {
        return invoke(url, HttpMethod.DELETE, new JsonContent(json));
    }

    @SneakyThrows
    public ResponseContent execute(Request request) {
        ensureBlockingCallAllowed();
        CompletableFuture<ResponseContent> future = executeAsync(request);
        try {
            return future.get(callTimeoutMillis(request != null ? request.timeoutMillis : 0), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    public CompletableFuture<ResponseContent> executeAsync(Request request) {
        if (request == null) {
            CompletableFuture<ResponseContent> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("request is null"));
            return future;
        }
        HttpHeaders headers = requestHeadersSnapshot();
        headers.set(request.headers);
        Proxy requestProxy = request.proxy != null ? request.proxy : proxy;
        boolean requestCookie = request.enableCookie != null ? request.enableCookie : enableCookie;
        return invokeAsync(request.url, request.method, request.body, headers, requestProxy, requestCookie, request.timeoutMillis);
    }

    public Tuple<RequestContent, ResponseContent> forward(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String forwardUrl) {
        return forward(servletRequest, servletResponse, forwardUrl, null);
    }

    @SneakyThrows
    public Tuple<RequestContent, ResponseContent> forward(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String forwardUrl,
                                                          BiFunc<RequestContent, RequestContent> requestInterceptor) {
        HttpHeaders headers = new DefaultHttpHeaders();
        for (String name : Collections.list(servletRequest.getHeaderNames())) {
            if (Strings.equalsIgnoreCase(name, HttpHeaderNames.HOST)
                    || Strings.equalsIgnoreCase(name, HttpHeaderNames.CONTENT_LENGTH)
                    || Strings.equalsIgnoreCase(name, HttpHeaderNames.TRANSFER_ENCODING)) {
                continue;
            }
            headers.set(name, servletRequest.getHeader(name));
        }

        String query = servletRequest.getQueryString();
        if (!Strings.isEmpty(query)) {
            forwardUrl += (forwardUrl.lastIndexOf("?") == -1 ? "?" : "&") + query;
        }

        HttpMethod method = HttpMethod.valueOf(servletRequest.getMethod());
        RequestContent content;
        if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
            content = EmptyContent.INSTANCE;
        } else {
            content = new StreamContent(servletRequest.getContentType(), servletRequest.getInputStream());
        }
        if (requestInterceptor != null) {
            RequestContent intercepted = requestInterceptor.invoke(content);
            if (intercepted != null) {
                content = intercepted;
            }
        }

        ResponseContent response = invoke(forwardUrl, method, content, headers);
        servletResponse.setStatus(response.getStatusCode());
        for (Map.Entry<String, String> header : response.responseHeaders()) {
            if (Strings.equalsIgnoreCase(header.getKey(), HttpHeaderNames.SET_COOKIE)) {
                servletResponse.addHeader(header.getKey(), header.getValue());
            } else {
                servletResponse.setHeader(header.getKey(), header.getValue());
            }
        }
        Long length = responseLength(response);
        if (length != null && length <= Integer.MAX_VALUE) {
            servletResponse.setContentLength(length.intValue());
        }
        String contentType = response.responseHeaders().get(HttpHeaderNames.CONTENT_TYPE);
        if (!Strings.isEmpty(contentType)) {
            servletResponse.setContentType(contentType);
        }
        ServletOutputStream out = servletResponse.getOutputStream();
        IOStream.copy(response.responseStream(), IOStream.NON_READ_FULLY, out);
        return Tuple.of(content, response);
    }

    private Long responseLength(ResponseContent response) {
        String value = response.responseHeaders().get(HttpHeaderNames.CONTENT_LENGTH);
        if (!Strings.isEmpty(value)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return response.stream.getLength();
        } catch (Throwable e) {
            return null;
        }
    }

    @SneakyThrows
    ResponseContent invoke(String url, HttpMethod method, RequestContent content) {
        return invoke(url, method, content, requestHeadersSnapshot());
    }

    @SneakyThrows
    ResponseContent invoke(String url, HttpMethod method, RequestContent content, HttpHeaders requestHeaders) {
        ensureBlockingCallAllowed();
        CompletableFuture<ResponseContent> future = invokeAsync(url, method, content, requestHeaders, proxy, enableCookie, 0);
        try {
            return future.get(callTimeoutMillis(0), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    CompletableFuture<ResponseContent> invokeAsync(String url, HttpMethod method, RequestContent content, HttpHeaders requestHeaders,
                                                   Proxy requestProxy, boolean cookieEnabled, int timeoutMillis) {
        metrics.requests.incrementAndGet();
        CompletableFuture<ResponseContent> future = new CompletableFuture<>();
        URI uri;
        try {
            uri = URI.create(url);
            if (Strings.isEmpty(uri.getScheme()) || Strings.isEmpty(uri.getHost())) {
                throw new IllegalArgumentException("Invalid http url " + url);
            }
        } catch (Throwable e) {
            metrics.failed.incrementAndGet();
            future.completeExceptionally(e);
            tryClose(content);
            return future;
        }
        PoolKey key = new PoolKey(uri, requestProxy);
        FixedChannelPool pool = pools.computeIfAbsent(key, this::newPool);
        AtomicReference<Channel> acquiredChannel = new AtomicReference<>();
        long startNanos = System.nanoTime();
        int requestTimeout = timeoutMillis > 0 ? timeoutMillis : readWriteTimeoutMillis;

        pool.acquire().addListener((io.netty.util.concurrent.Future<Channel> f) -> {
            if (!f.isSuccess()) {
                metrics.failed.incrementAndGet();
                future.completeExceptionally(f.cause());
                tryClose(content);
                return;
            }
            Channel channel = f.getNow();
            acquiredChannel.set(channel);
            if (future.isDone()) {
                release(channel, pool, true);
                return;
            }
            RequestState state = new RequestState(uri, key, pool, future, requestTimeout, requestHeaders, content,
                    metrics, startNanos, cookieEnabled, enableLog);
            channel.attr(STATE_KEY).set(state);
            state.timeoutFuture = channel.eventLoop().schedule(() -> {
                metrics.timeout.incrementAndGet();
                fail(channel, new TimeoutException("HTTP request timeout " + requestTimeout + "ms: " + url));
            }, requestTimeout, TimeUnit.MILLISECONDS);
            try {
                writeRequest(channel, uri, method, content, state);
            } catch (Throwable e) {
                fail(channel, e);
            }
        });

        future.whenComplete((r, e) -> {
            if (!future.isCancelled()) {
                return;
            }
            Channel channel = acquiredChannel.get();
            if (channel != null) {
                fail(channel, new TimeoutException("HTTP request cancelled: " + url));
            }
        });
        return future;
    }

    private void ensureBlockingCallAllowed() {
        Thread current = Thread.currentThread();
        for (EventExecutor executor : Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true)) {
            if (executor.inEventLoop(current)) {
                throw new IllegalStateException("HttpClientV2 sync API cannot be called from Netty EventLoop; use executeAsync instead");
            }
        }
    }

    private int callTimeoutMillis(int requestTimeoutMillis) {
        int rw = requestTimeoutMillis > 0 ? requestTimeoutMillis : readWriteTimeoutMillis;
        return Math.max(connectTimeoutMillis + rw, rw + (rw >>> 1));
    }

    private FixedChannelPool newPool(PoolKey key) {
        SocketConfig config = new SocketConfig();
        config.setConnectTimeoutMillis(connectTimeoutMillis);
        Bootstrap bootstrap = Sockets.bootstrap(config, key.unresolvedAddress(), null)
                .remoteAddress(key.unresolvedAddress());
        if (key.proxyType != Proxy.Type.DIRECT) {
            // 保持目标地址 unresolved，让 HTTP CONNECT / SOCKS 交给代理端解析。
            bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
        }
        return new FixedChannelPool(bootstrap, new AbstractChannelPoolHandler() {
            @Override
            public void channelCreated(Channel ch) throws Exception {
                initChannel(ch, key);
            }
        }, ChannelHealthChecker.ACTIVE, FixedChannelPool.AcquireTimeoutAction.FAIL,
                connectTimeoutMillis, maxConnectionsPerHost, maxPendingAcquires, true, true);
    }

    private void initChannel(Channel ch, PoolKey key) throws Exception {
        ChannelPipeline p = ch.pipeline();
        ProxyHandler proxyHandler = newProxyHandler(key);
        if (proxyHandler != null) {
            proxyHandler.setConnectTimeoutMillis(connectTimeoutMillis);
            p.addLast(proxyHandler);
        }
        if (key.isHttps()) {
            p.addLast(sslContext.newHandler(ch.alloc(), key.host, key.port));
        }
        p.addLast(new ReadTimeoutHandler(readWriteTimeoutMillis, TimeUnit.MILLISECONDS),
                new WriteTimeoutHandler(readWriteTimeoutMillis, TimeUnit.MILLISECONDS),
                new HttpClientCodec(),
                new HttpContentDecompressor(),
                new ChunkedWriteHandler(),
                new ClientInboundHandler());
    }

    private ProxyHandler newProxyHandler(PoolKey key) {
        if (key.proxyType == Proxy.Type.DIRECT) {
            return null;
        }
        if (key.proxyAddress == null) {
            throw new IllegalArgumentException("Proxy address is required");
        }
        boolean hasAuth = !Strings.isEmpty(key.proxyUsername);
        if (key.proxyType == Proxy.Type.HTTP) {
            return hasAuth ? new HttpProxyHandler(key.proxyAddress, key.proxyUsername, key.proxyPassword)
                    : new HttpProxyHandler(key.proxyAddress);
        }
        if (key.proxyType == Proxy.Type.SOCKS) {
            return hasAuth ? new Socks5ProxyHandler(key.proxyAddress, key.proxyUsername, key.proxyPassword)
                    : new Socks5ProxyHandler(key.proxyAddress);
        }
        return null;
    }

    private void writeRequest(Channel channel, URI uri, HttpMethod method, RequestContent content, RequestState state) {
        String requestUri = requestUri(uri);
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(state.requestHeaders);
        if (!headers.contains(HttpHeaderNames.HOST)) {
            headers.set(HttpHeaderNames.HOST, hostHeader(uri, state.key));
        }
        if (!headers.contains(HttpHeaderNames.CONNECTION)) {
            headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        if (!headers.contains(HttpHeaderNames.ACCEPT_ENCODING)) {
            headers.set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
        }
        if (state.cookieEnabled) {
            String cookie = cookieJar.loadForRequest(uri);
            if (!Strings.isEmpty(cookie)) {
                headers.add(HttpHeaderNames.COOKIE, cookie);
            }
        }
        String contentType = content.contentType();
        if (!Strings.isEmpty(contentType)) {
            headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }

        if (!content.streaming()) {
            ByteBuf body = content.toFullContent(channel);
            DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, requestUri, body);
            request.headers().set(headers);
            HttpUtil.setContentLength(request, body.readableBytes());
            state.metrics.uploadBytes.addAndGet(body.readableBytes());
            channel.writeAndFlush(request).addListener(f -> {
                state.closeContent();
                if (!f.isSuccess()) {
                    fail(channel, f.cause());
                }
            });
            return;
        }

        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, requestUri);
        request.headers().set(headers);
        HttpUtil.setTransferEncodingChunked(request, true);
        channel.writeAndFlush(request).addListener(f -> {
            if (!f.isSuccess()) {
                state.closeContent();
                fail(channel, f.cause());
                return;
            }
            Tasks.run(() -> {
                try {
                    content.writeStreaming(channel, state);
                } catch (Throwable e) {
                    fail(channel, e);
                } finally {
                    state.closeContent();
                }
            });
        });
    }

    private String requestUri(URI uri) {
        String rawPath = uri.getRawPath();
        if (Strings.isEmpty(rawPath)) {
            rawPath = "/";
        }
        String rawQuery = uri.getRawQuery();
        return Strings.isEmpty(rawQuery) ? rawPath : rawPath + "?" + rawQuery;
    }

    private String hostHeader(URI uri, PoolKey key) {
        int port = key.port;
        if (("http".equals(key.scheme) && port == 80) || ("https".equals(key.scheme) && port == 443)) {
            return uri.getHost();
        }
        return uri.getHost() + ":" + port;
    }

    static void fail(Channel channel, Throwable cause) {
        RequestState state = channel.attr(STATE_KEY).get();
        channel.attr(STATE_KEY).set(null);
        if (state == null || !state.completed.compareAndSet(false, true)) {
            channel.close();
            return;
        }
        state.cancelTimeout();
        state.closeContent();
        state.metrics.failed.incrementAndGet();
        release(channel, state, false);
        state.future.completeExceptionally(cause);
    }

    static void release(Channel channel, RequestState state, boolean keepAlive) {
        release(channel, state.pool, keepAlive);
    }

    static void release(Channel channel, FixedChannelPool pool, boolean keepAlive) {
        if (!keepAlive || !channel.isActive()) {
            channel.close();
        }
        pool.release(channel).addListener(f -> {
            if (!f.isSuccess()) {
                channel.close();
            }
        });
    }

    static void awaitWrite(ChannelFuture future, int timeoutMillis) throws Exception {
        if (!future.await(timeoutMillis)) {
            throw new TimeoutException("HTTP write timeout");
        }
        if (!future.isSuccess()) {
            Throwable cause = future.cause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    public static String buildUrl(String url, Map<String, Object> queryString) {
        if (url == null) {
            url = Strings.EMPTY;
        }
        if (queryString == null) {
            return url;
        }

        Map<String, Object> query = (Map) decodeQueryString(url);
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

    public static Map<String, String> decodeHeader(String raw) {
        return decodeHeader(Arrays.asList(Strings.split(raw.trim(), "\n")));
    }

    public static Map<String, String> decodeHeader(List<String> pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (pairs == null || pairs.isEmpty()) {
            return map;
        }

        for (String pair : pairs) {
            int idx = pair.indexOf(":");
            if (idx == -1) {
                continue;
            }
            String key = pair.substring(0, idx);
            String value = pair.length() > idx + 1 ? pair.substring(idx + 1).trim() : Strings.EMPTY;
            map.put(key, value);
        }
        return map;
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

    public static void saveRawCookie(@NonNull String url, @NonNull String cookie) {
        COOKIES.saveRawCookie(URI.create(url), cookie);
    }

    public static final class HttpClientCookieJar {
        final HttpClientCookieStorage storage;
        final CopyOnWriteArrayList<StoredCookie> cookies;

        public HttpClientCookieJar() {
            this(new MemoryCookieStorage());
        }

        public HttpClientCookieJar(HttpClientCookieStorage storage) {
            this.storage = storage != null ? storage : new MemoryCookieStorage();
            cookies = new CopyOnWriteArrayList<>(this.storage.loadAll());
        }

        public static HttpClientCookieJar memory() {
            return new HttpClientCookieJar(new MemoryCookieStorage());
        }

        public static HttpClientCookieJar h2(EntityDatabase db) {
            return new HttpClientCookieJar(new H2CookieStorage(db));
        }

        public String loadForRequest(URI uri) {
            long now = System.currentTimeMillis();
            List<Cookie> matched = new ArrayList<>();
            for (StoredCookie stored : cookies) {
                if (stored.isExpired(now)) {
                    cookies.remove(stored);
                    storage.remove(stored);
                    continue;
                }
                if (stored.matches(uri)) {
                    matched.add(stored.cookie);
                }
            }
            return matched.isEmpty() ? null : ClientCookieEncoder.STRICT.encode(matched);
        }

        public void saveFromResponse(URI uri, List<String> setCookies) {
            if (setCookies == null || setCookies.isEmpty()) {
                return;
            }
            for (String raw : setCookies) {
                Cookie cookie = ClientCookieDecoder.STRICT.decode(raw);
                if (cookie != null) {
                    save(uri, cookie);
                }
            }
        }

        public void saveRawCookie(URI uri, String rawCookie) {
            if (Strings.isEmpty(rawCookie)) {
                return;
            }
            for (String pair : Strings.split(rawCookie, ";")) {
                int i = pair.indexOf("=");
                if (i <= 0) {
                    continue;
                }
                DefaultCookie cookie = new DefaultCookie(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
                save(uri, cookie);
            }
        }

        void save(URI uri, Cookie cookie) {
            if (Strings.isEmpty(cookie.domain())) {
                cookie.setDomain(uri.getHost());
            }
            if (Strings.isEmpty(cookie.path())) {
                cookie.setPath("/");
            }
            StoredCookie stored = new StoredCookie(cookie);
            cookies.remove(stored);
            if (stored.isExpired(System.currentTimeMillis())) {
                storage.remove(stored);
                return;
            }
            cookies.add(stored);
            storage.save(stored);
        }

        public void clearSession() {
            long now = System.currentTimeMillis();
            for (StoredCookie cookie : cookies) {
                if (cookie.session || cookie.isExpired(now)) {
                    cookies.remove(cookie);
                    storage.remove(cookie);
                }
            }
        }

        public void clear() {
            cookies.clear();
            storage.clear();
        }
    }

    public interface HttpClientCookieStorage {
        List<StoredCookie> loadAll();

        void save(StoredCookie cookie);

        void remove(StoredCookie cookie);

        void clear();
    }

    public static final class MemoryCookieStorage implements HttpClientCookieStorage {
        final CopyOnWriteArrayList<StoredCookie> store = new CopyOnWriteArrayList<>();

        @Override
        public List<StoredCookie> loadAll() {
            return new ArrayList<>(store);
        }

        @Override
        public void save(StoredCookie cookie) {
            store.remove(cookie);
            store.add(cookie);
        }

        @Override
        public void remove(StoredCookie cookie) {
            store.remove(cookie);
        }

        @Override
        public void clear() {
            store.clear();
        }
    }

    public static final class H2CookieStorage implements HttpClientCookieStorage {
        final EntityDatabase db;

        public H2CookieStorage(EntityDatabase db) {
            this.db = db != null ? db : EntityDatabase.DEFAULT;
            this.db.createMapping(CookieEntity.class);
        }

        @Override
        public List<StoredCookie> loadAll() {
            long now = System.currentTimeMillis();
            List<StoredCookie> result = new ArrayList<>();
            List<CookieEntity> entities = db.findBy(new EntityQueryLambda<>(CookieEntity.class));
            for (CookieEntity entity : entities) {
                StoredCookie cookie = entity.toStoredCookie(now);
                if (cookie == null || cookie.isExpired(now)) {
                    db.deleteById(CookieEntity.class, entity.id);
                    continue;
                }
                result.add(cookie);
            }
            return result;
        }

        @Override
        public void save(StoredCookie cookie) {
            if (cookie.session) {
                remove(cookie);
                return;
            }
            db.save(CookieEntity.from(cookie), true);
        }

        @Override
        public void remove(StoredCookie cookie) {
            db.deleteById(CookieEntity.class, CookieEntity.id(cookie.name, cookie.domain, cookie.path));
        }

        @Override
        public void clear() {
            db.truncateMapping(CookieEntity.class);
        }
    }

    public static final class CookieEntity implements Serializable {
        private static final long serialVersionUID = -1714170629027078331L;

        @DbColumn(primaryKey = true, length = 512)
        public String id;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 255)
        public String domain;
        public String path;
        public String name;
        public String value;
        public Boolean secure;
        public Boolean httpOnly;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
        public Long expiresAt;

        public static CookieEntity from(StoredCookie cookie) {
            CookieEntity entity = new CookieEntity();
            entity.id = id(cookie.name, cookie.domain, cookie.path);
            entity.domain = cookie.domain;
            entity.path = cookie.path;
            entity.name = cookie.name;
            entity.value = cookie.cookie.value();
            entity.secure = cookie.secure;
            entity.httpOnly = cookie.httpOnly;
            entity.expiresAt = cookie.expiresAt;
            return entity;
        }

        public static String id(String name, String domain, String path) {
            return (domain == null ? Strings.EMPTY : domain.toLowerCase(Locale.ENGLISH)) + "|" + (path == null ? "/" : path) + "|" + name;
        }

        StoredCookie toStoredCookie(long now) {
            if (Strings.isEmpty(name) || Strings.isEmpty(domain) || Strings.isEmpty(path) || expiresAt == null || expiresAt <= now) {
                return null;
            }
            DefaultCookie cookie = new DefaultCookie(name, value != null ? value : Strings.EMPTY);
            cookie.setDomain(domain);
            cookie.setPath(path);
            cookie.setSecure(Boolean.TRUE.equals(secure));
            cookie.setHttpOnly(Boolean.TRUE.equals(httpOnly));
            long seconds = Math.max(1L, (expiresAt - now + 999L) / 1000L);
            cookie.setMaxAge(seconds);
            return new StoredCookie(cookie, false, expiresAt);
        }
    }

    public static final class StoredCookie {
        final Cookie cookie;
        final String name;
        final String domain;
        final String path;
        final boolean secure;
        final boolean httpOnly;
        final boolean session;
        final long expiresAt;

        StoredCookie(Cookie cookie) {
            this(cookie, cookie.maxAge() == Long.MIN_VALUE,
                    cookie.maxAge() == Long.MIN_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cookie.maxAge()));
        }

        StoredCookie(Cookie cookie, boolean session, long expiresAt) {
            this.cookie = cookie;
            name = cookie.name();
            domain = cookie.domain() != null ? cookie.domain().toLowerCase(Locale.ENGLISH) : Strings.EMPTY;
            path = cookie.path() != null ? cookie.path() : "/";
            secure = cookie.isSecure();
            httpOnly = cookie.isHttpOnly();
            this.session = session;
            this.expiresAt = expiresAt;
        }

        boolean isExpired(long now) {
            return expiresAt <= now;
        }

        boolean matches(URI uri) {
            if (secure && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ENGLISH);
            if (!host.equals(domain) && !host.endsWith("." + domain)) {
                return false;
            }
            String rawPath = uri.getRawPath();
            if (Strings.isEmpty(rawPath)) {
                rawPath = "/";
            }
            return rawPath.startsWith(path);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof StoredCookie)) {
                return false;
            }
            StoredCookie that = (StoredCookie) o;
            return name.equals(that.name) && domain.equals(that.domain) && path.equals(that.path);
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + domain.hashCode();
            result = 31 * result + path.hashCode();
            return result;
        }
    }
}
