package org.rx.net.http;

import com.alibaba.fastjson2.JSON;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.pool.AbstractChannelPoolHandler;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.handler.codec.http.*;
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
import org.rx.bean.Tuple;
import org.rx.core.Constants;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.core.Sys;
import org.rx.core.Tasks;
import org.rx.io.Bytes;
import org.rx.io.DuplexStream;
import org.rx.io.Files;
import org.rx.io.HybridStream;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.exception.InvalidException;
import org.rx.net.SocketConfig;
import org.rx.net.Sockets;
import org.rx.net.dns.DnsClient;
import org.rx.util.function.BiFunc;

import java.io.File;
import java.io.InputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.rx.core.Extends.tryClose;

@Slf4j
public class HttpClient implements AutoCloseable {
    public static final String FORM_TYPE = "application/x-www-form-urlencoded;charset=UTF-8";
    public static final String JSON_TYPE = "application/json; charset=UTF-8";
    static final AttributeKey<RequestState> STATE_KEY = AttributeKey.valueOf("HttpClientState");
    static final int LOG_PREVIEW_BYTES = 512;
    static final int LOG_PREVIEW_CHARS = 512;
    private static final AtomicLongFieldUpdater<HttpClient> REQUESTS_UPDATER =
            AtomicLongFieldUpdater.newUpdater(HttpClient.class, "metricRequests");
    private static final AtomicLongFieldUpdater<HttpClient> SUCCESS_UPDATER =
            AtomicLongFieldUpdater.newUpdater(HttpClient.class, "metricSuccess");
    private static final AtomicLongFieldUpdater<HttpClient> FAILED_UPDATER =
            AtomicLongFieldUpdater.newUpdater(HttpClient.class, "metricFailed");
    private static final AtomicLongFieldUpdater<HttpClient> TIMEOUT_UPDATER =
            AtomicLongFieldUpdater.newUpdater(HttpClient.class, "metricTimeout");
    private static final AtomicLongFieldUpdater<HttpClient> UPLOAD_BYTES_UPDATER =
            AtomicLongFieldUpdater.newUpdater(HttpClient.class, "metricUploadBytes");
    private static final AtomicLongFieldUpdater<HttpClient> DOWNLOAD_BYTES_UPDATER =
            AtomicLongFieldUpdater.newUpdater(HttpClient.class, "metricDownloadBytes");
    private static final AtomicLongFieldUpdater<HttpClient> TOTAL_LATENCY_UPDATER =
            AtomicLongFieldUpdater.newUpdater(HttpClient.class, "metricTotalLatencyNanos");
    private static final AtomicLongFieldUpdater<HttpClient> MAX_LATENCY_UPDATER =
            AtomicLongFieldUpdater.newUpdater(HttpClient.class, "metricMaxLatencyNanos");

    /** 与 {@link #getDefault()} 为同一实例，双检锁懒初始化 */
    private static volatile HttpClient DEFAULT;

    final Map<PoolKey, FixedChannelPool> pools = new ConcurrentHashMap<>();
    private volatile Metrics metrics;
    private volatile long metricRequests;
    private volatile long metricSuccess;
    private volatile long metricFailed;
    private volatile long metricTimeout;
    private volatile long metricUploadBytes;
    private volatile long metricDownloadBytes;
    private volatile long metricTotalLatencyNanos;
    private volatile long metricMaxLatencyNanos;
    final Set<Response> activeResponses = Collections.newSetFromMap(new ConcurrentHashMap<Response, Boolean>());
    final HttpClientConfig config;
    final HttpHeaders reqHeaders = new DefaultHttpHeaders();

    public static HttpClient getDefault() {
        HttpClient d = DEFAULT;
        if (d != null) {
            return d;
        }
        synchronized (HttpClient.class) {
            d = DEFAULT;
            if (d == null) {
                DEFAULT = d = new HttpClient();
            }
            return d;
        }
    }

    public HttpClient() {
        this(new HttpClientConfig());
    }

    public HttpClient(HttpClientCookieJar cookieJar) {
        this(new HttpClientConfig().setCookieJar(cookieJar));
    }

    public HttpClient(HttpClientConfig config) {
        this.config = config != null ? config : new HttpClientConfig();
    }

    public HttpClientConfig config() {
        return config;
    }

    public Metrics getMetrics() {
        return ensureMetrics();
    }

    private Metrics ensureMetrics() {
        Metrics m = metrics;
        if (m != null) {
            return m;
        }
        synchronized (this) {
            m = metrics;
            if (m == null) {
                metrics = m = new Metrics(this);
            }
            return m;
        }
    }

    public HttpClient requestUserAgent() {
        requestHeaders().set(HttpHeaderNames.USER_AGENT, RxConfig.INSTANCE.getNet().getUserAgent());
        return this;
    }

    public HttpClient requestCookie(String rawCookie) {
        requestHeaders().set(HttpHeaderNames.COOKIE, rawCookie);
        return this;
    }

    public HttpHeaders requestHeaders() {
        return reqHeaders;
    }

    private HttpHeaders mergeRequestHeaders(HttpHeaders requestHeaders) {
        boolean hasDefaults = !reqHeaders.isEmpty();
        boolean hasRequestHeaders = requestHeaders != null && !requestHeaders.isEmpty();
        if (!hasDefaults) {
            return copyHeaders(requestHeaders);
        }
        if (!hasRequestHeaders) {
            return copyHeaders(reqHeaders);
        }
        HttpHeaders headers = new DefaultHttpHeaders(false);
        headers.set(reqHeaders);
        headers.set(requestHeaders);
        return headers;
    }

    private static HttpHeaders copyHeaders(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return EmptyHttpHeaders.INSTANCE;
        }
        HttpHeaders copy = new DefaultHttpHeaders(false);
        copy.set(headers);
        return copy;
    }

    private static HttpHeaders mutableHeaders(HttpHeaders headers) {
        HttpHeaders copy = new DefaultHttpHeaders(false);
        if (headers != null && !headers.isEmpty()) {
            copy.set(headers);
        }
        return copy;
    }

    private static HttpHeaders ensureMutableHeaders(Request request) {
        HttpHeaders headers = request.headers;
        if (headers == null || headers == EmptyHttpHeaders.INSTANCE) {
            request.headers = headers = new DefaultHttpHeaders(false);
        }
        return headers;
    }

    private static boolean cacheLookupAllowed(Request request, boolean cookieEnabled) {
        if (request == null || !HttpMethod.GET.equals(request.method()) || cookieEnabled) {
            return false;
        }
        HttpHeaders headers = request.headers;
        if (headers == null || headers.isEmpty()) {
            return true;
        }
        return !headers.contains(HttpHeaderNames.AUTHORIZATION)
                && !headers.contains(HttpHeaderNames.PROXY_AUTHORIZATION)
                && !headers.contains(HttpHeaderNames.COOKIE);
    }

    private static boolean cacheStoreAllowed(Request request) {
        HttpHeaders headers = request != null ? request.headers : null;
        return headers == null || !hasCacheDirective(headers.get(HttpHeaderNames.CACHE_CONTROL), "no-store");
    }

    private static boolean cacheBypass(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        return hasCacheDirective(headers.get(HttpHeaderNames.CACHE_CONTROL), "no-store");
    }

    private static boolean forceCacheRevalidate(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        String cacheControl = headers.get(HttpHeaderNames.CACHE_CONTROL);
        return hasCacheDirective(cacheControl, "no-cache")
                || hasCacheDirective(cacheControl, "max-age=0")
                || Strings.equalsIgnoreCase(headers.get(HttpHeaderNames.PRAGMA), "no-cache");
    }

    private static boolean hasCacheDirective(String cacheControl, String directive) {
        if (Strings.isEmpty(cacheControl) || Strings.isEmpty(directive)) {
            return false;
        }
        String[] tokens = cacheControl.split(",");
        for (String token : tokens) {
            String value = token.trim();
            if (Strings.equalsIgnoreCase(value, directive) || Strings.startsWithIgnoreCase(value, directive)) {
                return true;
            }
        }
        return false;
    }

    private Request snapshotRequest(Request request, HttpClientConfig cfg) {
        Proxy requestProxy = request.proxy != null ? request.proxy : cfg.getProxy();
        int timeoutMillis = request.timeoutMillis > 0 ? request.timeoutMillis : cfg.getReadWriteTimeoutMillis();
        boolean cookieEnabled = cfg.getCookieJar() != null
                && (request.enableCookie == null || request.enableCookie);
        boolean followRedirects = request.followRedirects != null ? request.followRedirects : cfg.isFollowRedirects();
        int maxRedirects = request.maxRedirects != null ? Math.max(0, request.maxRedirects) : cfg.getMaxRedirects();
        boolean enableLog = request.enableLog != null ? request.enableLog : cfg.isEnableLog();
        Request snapshot = new Request(request.method, request.url);
        snapshot.body = request.body != null ? request.body : RequestContent.EMPTY;
        snapshot.timeoutMillis = timeoutMillis;
        snapshot.proxy = requestProxy;
        snapshot.enableCookie = cookieEnabled;
        snapshot.followRedirects = followRedirects;
        snapshot.maxRedirects = maxRedirects;
        snapshot.enableLog = enableLog;
        snapshot.headers = mergeRequestHeaders(request.headers);
        return snapshot;
    }

    private static String requestLogText(Request request) {
        if (request == null) {
            return Strings.EMPTY;
        }
        RequestContent content = request.body();
        if (content == null || content == RequestContent.EMPTY) {
            return Strings.EMPTY;
        }
        if (content instanceof JsonContent) {
            return previewText(Sys.toJsonString(((JsonContent) content).getJson()));
        }
        if (content instanceof FormContent) {
            return previewText(Sys.toJsonString(((FormContent) content).getForms()));
        }
        if (content instanceof MultipartContent) {
            MultipartContent multipart = (MultipartContent) content;
            return previewText(String.format("{\"forms\":%s,\"files\":%s}",
                    Sys.toJsonString(multipart.forms), Sys.toJsonString(multipart.files.keySet())));
        }
        if (content instanceof ByteBufContent) {
            ByteBufContent byteBufContent = (ByteBufContent) content;
            String contentType = byteBufContent.contentType();
            int readableBytes = byteBufContent.data.readableBytes();
            if (!isTextualContentType(contentType)) {
                return "<" + (contentType == null ? "binary" : contentType) + ", " + readableBytes + " bytes>";
            }
            ByteBuf buf = byteBufContent.data.duplicate();
            int length = Math.min(buf.readableBytes(), LOG_PREVIEW_BYTES);
            String text = buf.toString(buf.readerIndex(), length, parseCharset(contentType));
            if (buf.readableBytes() > length) {
                text += "...";
            }
            return previewText(text);
        }
        if (content instanceof StreamContent) {
            return "<stream " + (content.contentType() == null ? "binary" : content.contentType()) + ">";
        }
        return "<" + content.getClass().getSimpleName() + ">";
    }

    private static String responseLogText(Response response) {
        if (response == null) {
            return Strings.EMPTY;
        }
        String contentType = response.header(HttpHeaderNames.CONTENT_TYPE.toString());
        long contentLength;
        try {
            contentLength = response.body.getLength();
        } catch (Throwable e) {
            contentLength = -1L;
        }
        if (!isTextualContentType(contentType)) {
            return "<" + (contentType == null ? "binary" : contentType) + (contentLength >= 0 ? ", " + contentLength + " bytes" : Strings.EMPTY) + ">";
        }
        DuplexStream stream = response.bodyStream();
        ByteBuf buf = Bytes.heapBuffer(LOG_PREVIEW_BYTES);
        try {
            int read = stream.read(buf, LOG_PREVIEW_BYTES);
            if (read <= 0) {
                return Strings.EMPTY;
            }
            String text = buf.toString(buf.readerIndex(), read, response.charset());
            if (read == LOG_PREVIEW_BYTES && stream.read() != Constants.IO_EOF) {
                text += "...";
            }
            return previewText(text);
        } catch (Throwable e) {
            return "<preview " + throwableSummary(e) + ">";
        } finally {
            buf.release();
        }
    }

    private static boolean isTextualContentType(String contentType) {
        if (Strings.isEmpty(contentType)) {
            return true;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
                || lower.contains("json")
                || lower.contains("xml")
                || lower.contains("form-urlencoded")
                || lower.contains("javascript")
                || lower.contains("html");
    }

    private static Charset parseCharset(String contentType) {
        if (!Strings.isEmpty(contentType)) {
            String[] parts = contentType.split(";");
            for (String part : parts) {
                String value = part.trim();
                if (Strings.startsWithIgnoreCase(value, "charset=")) {
                    try {
                        return Charset.forName(value.substring("charset=".length()));
                    } catch (Throwable e) {
                        break;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String previewText(String text) {
        if (Strings.isEmpty(text)) {
            return Strings.EMPTY;
        }
        String normalized = text.replace("\r", "\\r").replace("\n", "\\n");
        return normalized.length() <= LOG_PREVIEW_CHARS ? normalized : normalized.substring(0, LOG_PREVIEW_CHARS) + "...";
    }

    private static String throwableSummary(Throwable e) {
        Throwable root = org.apache.commons.lang3.exception.ExceptionUtils.getRootCause(e);
        Throwable actual = root == null ? e : root;
        if (actual == null) {
            return "unknown";
        }
        String message = actual.getMessage();
        return Strings.isEmpty(message) ? actual.getClass().getSimpleName() : actual.getClass().getSimpleName() + ": " + message;
    }

    @Override
    public void close() {
        Response[] responses = activeResponses.toArray(new Response[0]);
        for (Response response : responses) {
            tryClose(response);
        }
        activeResponses.clear();
        for (FixedChannelPool pool : pools.values()) {
            pool.close();
        }
        pools.clear();
    }

    public static final class Metrics {
        private final HttpClient owner;

        Metrics(HttpClient owner) {
            this.owner = owner;
        }

        public long requests() {
            return owner.metricRequests;
        }

        public long success() {
            return owner.metricSuccess;
        }

        public long failed() {
            return owner.metricFailed;
        }

        public long timeout() {
            return owner.metricTimeout;
        }

        public long uploadBytes() {
            return owner.metricUploadBytes;
        }

        public long downloadBytes() {
            return owner.metricDownloadBytes;
        }

        public long totalLatencyNanos() {
            return owner.metricTotalLatencyNanos;
        }

        public long maxLatencyNanos() {
            return owner.metricMaxLatencyNanos;
        }

        public long usedDirectMemory() {
            return PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory();
        }
    }
    
    public static Request request(HttpMethod method, String url) {
        return new Request(method, url);
    }

    public static final class Request {
        private final HttpMethod method;
        private final String url;
        private HttpHeaders headers;
        private RequestContent body = RequestContent.EMPTY;
        private int timeoutMillis;
        private Proxy proxy;
        private Boolean enableCookie;
        private Boolean followRedirects;
        private Integer maxRedirects;
        private Boolean enableLog;

        Request(HttpMethod method, String url) {
            this.method = method;
            this.url = url;
        }

        public HttpMethod method() {
            return method;
        }

        public String url() {
            return url;
        }

        public HttpHeaders headers() {
            HttpHeaders h = headers;
            if (h == null) {
                headers = h = new DefaultHttpHeaders(false);
            }
            return h;
        }

        public Request header(CharSequence name, Object value) {
            headers().set(name, value);
            return this;
        }

        public Request headers(HttpHeaders requestHeaders) {
            if (requestHeaders != null && !requestHeaders.isEmpty()) {
                headers().set(requestHeaders);
            }
            return this;
        }

        public RequestContent body() {
            return body;
        }

        public Request body(RequestContent body) {
            this.body = body != null ? body : RequestContent.EMPTY;
            return this;
        }

        public Request bytes(byte[] bytes, CharSequence contentType) {
            return body(new ByteBufContent(contentType == null ? null : contentType.toString(), newContentBuffer(bytes)));
        }

        public Request bytes(ByteBuf bytes, CharSequence contentType) {
            return body(new ByteBufContent(contentType == null ? null : contentType.toString(), bytes));
        }

        public Request json(Object json) {
            return body(new JsonContent(json));
        }

        public Request form(Map<String, Object> forms) {
            return body(new FormContent(forms));
        }

        public Request multipart(Map<String, Object> forms, Map<String, DuplexStream> files) {
            return body(new MultipartContent(forms, files));
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

        public Request followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        public Request maxRedirects(int maxRedirects) {
            this.maxRedirects = maxRedirects;
            return this;
        }

        public Request enableLog(boolean enableLog) {
            this.enableLog = enableLog;
            return this;
        }

        public int timeoutMillis() {
            return timeoutMillis;
        }

        public Proxy proxy() {
            return proxy;
        }

        public Boolean enableCookie() {
            return enableCookie;
        }

        public Boolean followRedirects() {
            return followRedirects;
        }

        public Integer maxRedirects() {
            return maxRedirects;
        }

        public Boolean enableLog() {
            return enableLog;
        }
    }

    public interface RequestContent extends AutoCloseable {
        RequestContent EMPTY = new RequestContent() {
        };

        default String contentType() {
            return null;
        }

        default boolean streaming() {
            return false;
        }

        default ByteBuf toFullContent(Channel channel) {
            return channel.alloc().buffer(0, 0);
        }

        default void writeStreaming(Channel channel, RequestState state) throws Exception {
        }

        @Override
        default void close() {
        }
    }

    private static ByteBuf newUtf8Buffer(String value) {
        return Strings.isEmpty(value) ? Unpooled.EMPTY_BUFFER : ByteBufUtil.writeUtf8(PooledByteBufAllocator.DEFAULT, value);
    }

    private static ByteBuf newContentBuffer(byte[] value) {
        if (value == null || value.length == 0) {
            return Unpooled.EMPTY_BUFFER;
        }
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.ioBuffer(value.length, value.length);
        buf.writeBytes(value);
        return buf;
    }

    static class ByteBufContent implements RequestContent {
        final String contentType;
        final ByteBuf data;

        ByteBufContent(String contentType, ByteBuf data) {
            this.contentType = contentType;
            this.data = data != null ? data : Unpooled.EMPTY_BUFFER;
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
            return data.retainedDuplicate();
        }

        @Override
        public void writeStreaming(Channel channel, RequestState state) {
        }

        @Override
        public void close() {
            ReferenceCountUtil.release(data);
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
            int read;
            while (true) {
                if (!channel.isActive()) {
                    throw new IllegalStateException("Channel closed");
                }
                ByteBuf buf = channel.alloc().ioBuffer(Constants.HEAP_BUF_SIZE, Constants.HEAP_BUF_SIZE);
                read = buf.writeBytes(in, Constants.HEAP_BUF_SIZE);
                if (read == Constants.IO_EOF) {
                    buf.release();
                    break;
                }
                writer.write(buf, read);
            }
            writer.finish();
        }
    }

    public static final class JsonContent extends ByteBufContent {
        @Getter
        final Object json;

        JsonContent(Object json) {
            super(JSON_TYPE, newUtf8Buffer(Sys.toJsonString(json)));
            this.json = json;
        }
    }

    public static final class FormContent extends ByteBufContent {
        @Getter
        final Map<String, Object> forms;

        FormContent(Map<String, Object> forms) {
            super(FORM_TYPE, newUtf8Buffer(buildUrl(null, forms, false)));
            this.forms = forms != null ? forms : Collections.emptyMap();
        }
    }

    public static final class MultipartContent implements RequestContent {
        static final ByteBuf CRLF = Unpooled.unreleasableBuffer(Unpooled.directBuffer(2, 2)
                .writeByte('\r')
                .writeByte('\n')).asReadOnly();

        final Map<String, Object> forms;
        final Map<String, DuplexStream> files;
        final String boundary;
        final String contentType;
        final StringBuilder partPrefixBuilder = new StringBuilder(128);

        MultipartContent(Map<String, Object> forms, Map<String, DuplexStream> files) {
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
            for (Map.Entry<String, DuplexStream> entry : files.entrySet()) {
                DuplexStream stream = entry.getValue();
                if (stream == null) {
                    continue;
                }
                String fileName = Strings.isEmpty(stream.getName()) ? entry.getKey() : stream.getName();
                writeAscii(writer, partPrefix(entry.getKey(), fileName, Files.getMediaTypeFromName(fileName)));
                writeStream(writer, stream.rewind());
                writeBytes(writer, CRLF);
            }
            writeAscii(writer, "--" + boundary + "--\r\n");
            writer.finish();
        }

        private String partPrefix(String name, String fileName, String mediaType) {
            StringBuilder sb = partPrefixBuilder;
            sb.setLength(0);
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
            if (Strings.isEmpty(value)) {
                return;
            }
            ByteBuf buf = ByteBufUtil.writeUtf8(writer.channel.alloc(), value);
            writer.write(buf, buf.readableBytes());
        }

        private void writeBytes(UploadWriter writer, ByteBuf bytes) throws Exception {
            ByteBuf buf = bytes.retainedDuplicate();
            writer.write(buf, buf.readableBytes());
        }

        private void writeStream(UploadWriter writer, DuplexStream in) throws Exception {
            int read;
            while (true) {
                if (!writer.channel.isActive()) {
                    throw new IllegalStateException("Channel closed");
                }
                ByteBuf buf = writer.channel.alloc().ioBuffer(Constants.HEAP_BUF_SIZE, Constants.HEAP_BUF_SIZE);
                read = in.read(buf, Constants.HEAP_BUF_SIZE);
                if (read == Constants.IO_EOF) {
                    buf.release();
                    break;
                }
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
            if (channel.eventLoop().inEventLoop()) {
                throw new IllegalStateException("HTTP streaming upload cannot run on Netty EventLoop");
            }
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
            if (bytes > 0L) {
                UPLOAD_BYTES_UPDATER.addAndGet(state.client, bytes);
            }
            pendingBytes += bytes;
            pendingChunks++;
            if (pendingBytes >= state.uploadFlushBytes || pendingChunks >= state.uploadFlushChunks || !channel.isWritable()) {
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
                proxyAddress = resolveProxyAddress(proxy.address());
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

        static SocketAddress resolveProxyAddress(SocketAddress address) {
            if (!(address instanceof InetSocketAddress)) {
                return address;
            }

            InetSocketAddress endpoint = (InetSocketAddress) address;
            if (!endpoint.isUnresolved()) {
                return endpoint;
            }

            String host = endpoint.getHostString();
            if (Sockets.isValidIp(host)) {
                return new InetSocketAddress(Sockets.parseIpAddress(host), endpoint.getPort());
            }
            try {
                InetAddress resolved = DnsClient.directClient().resolve(host);
                if (resolved == null) {
                    throw new UnknownHostException(host);
                }
                return new InetSocketAddress(resolved, endpoint.getPort());
            } catch (Throwable e) {
                throw new InvalidException("Resolve proxy address {} fail", Sockets.toString(endpoint), e);
            }
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
        final Request request;
        final Request currentRequest;
        final URI uri;
        final PoolKey key;
        final FixedChannelPool pool;
        final CompletableFuture<Response> future;
        final HttpClientConfig config;
        final Proxy proxy;
        final int redirectCount;
        final int readWriteTimeoutMillis;
        final int responseOffloadThreshold;
        final int uploadFlushBytes;
        final int uploadFlushChunks;
        final HttpHeaders requestHeaders;
        final RequestContent content;
        final HttpClient client;
        final HttpClientCookieJar cookieJar;
        final HttpClientCache cache;
        final HttpClientCache.CacheEntry cacheEntry;
        final long startNanos;
        final boolean cookieEnabled;
        final boolean cacheStoreAllowed;
        final boolean followRedirects;
        final int maxRedirects;
        final boolean logEnabled;
        final String requestLogText;
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean contentClosed = new AtomicBoolean();
        CompletableFuture<Void> responseWriteTail = CompletableFuture.completedFuture(null);
        HttpResponse response;
        HybridStream body;
        ScheduledFuture<?> timeoutFuture;
        long responseBytes;
        boolean responseOffloaded;

        RequestState(Request request, Request currentRequest, URI uri, PoolKey key, FixedChannelPool pool, CompletableFuture<Response> future, HttpClientConfig config,
                     HttpClientCookieJar cookieJar, HttpClientCache cache, HttpClientCache.CacheEntry cacheEntry,
                     Proxy proxy, int redirectCount, int readWriteTimeoutMillis, HttpHeaders requestHeaders, RequestContent content, HttpClient client,
                     int responseOffloadThreshold, int uploadFlushBytes, int uploadFlushChunks,
                     long startNanos, boolean cookieEnabled, boolean cacheStoreAllowed, boolean followRedirects, int maxRedirects, boolean logEnabled) {
            this.request = request;
            this.currentRequest = currentRequest;
            this.uri = uri;
            this.key = key;
            this.pool = pool;
            this.future = future;
            this.config = config;
            this.proxy = proxy;
            this.redirectCount = redirectCount;
            this.readWriteTimeoutMillis = readWriteTimeoutMillis;
            this.responseOffloadThreshold = responseOffloadThreshold;
            this.uploadFlushBytes = uploadFlushBytes;
            this.uploadFlushChunks = uploadFlushChunks;
            this.requestHeaders = requestHeaders != null ? requestHeaders : EmptyHttpHeaders.INSTANCE;
            this.content = content;
            this.client = client;
            this.cookieJar = cookieJar;
            this.cache = cache;
            this.cacheEntry = cacheEntry;
            this.startNanos = startNanos;
            this.cookieEnabled = cookieEnabled;
            this.cacheStoreAllowed = cacheStoreAllowed;
            this.followRedirects = followRedirects;
            this.maxRedirects = maxRedirects;
            this.logEnabled = logEnabled;
            requestLogText = logEnabled ? HttpClient.requestLogText(currentRequest) : null;
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
            synchronized (this) {
                responseWriteTail = responseWriteTail.thenRunAsync(() -> {
                    try {
                        body.write(retained, retained.readableBytes());
                        if (readableBytes > 0L) {
                            DOWNLOAD_BYTES_UPDATER.addAndGet(client, readableBytes);
                        }
                    } finally {
                        ReferenceCountUtil.release(retained);
                    }
                }, Tasks.executor());
                return responseWriteTail;
            }
        }

        CompletableFuture<Void> responseWriteTail() {
            synchronized (this) {
                return responseWriteTail;
            }
        }
    }

    static final class RedirectPlan {
        final String url;
        final HttpMethod method;
        final HttpHeaders headers;

        RedirectPlan(String url, HttpMethod method, HttpHeaders headers) {
            this.url = url;
            this.method = method;
            this.headers = headers;
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
                state.body = newResponseStream(state, len);
                state.responseOffloaded = state.body.isFileBacked();
            }

            if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                if (state.body == null) {
                    state.body = newResponseStream(state, null);
                    state.responseOffloaded = state.body.isFileBacked();
                }
                int readableBytes = content.content().readableBytes();
                if (readableBytes > 0) {
                    boolean offload = state.responseOffloaded || state.responseBytes + readableBytes > state.responseOffloadThreshold;
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
                    state.body.write(content.content(), readableBytes);
                    DOWNLOAD_BYTES_UPDATER.addAndGet(state.client, readableBytes);
                }
                if (msg instanceof LastHttpContent) {
                    completeAfterWrites(ctx.channel(), state);
                }
            }
        }

        private HybridStream newResponseStream(RequestState state, Long len) {
            int memoryLimit = state.responseOffloadThreshold <= 0 ? HybridStream.NON_MEMORY_SIZE :
                    Math.min(state.responseOffloadThreshold, Constants.MAX_HEAP_BUF_SIZE);
            if (len != null && memoryLimit > HybridStream.NON_MEMORY_SIZE && len > memoryLimit) {
                memoryLimit = HybridStream.NON_MEMORY_SIZE;
            }
            return new HybridStream(memoryLimit, false);
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
            if (channel.isActive()) {
                channel.config().setAutoRead(true);
            }
            release(channel, state, keepAlive);
            if (state.cookieEnabled) {
                Tasks.run(() -> completeFuture(state));
            } else {
                completeFuture(state);
            }
        }

        private void completeFuture(RequestState state) {
            try {
                if (state.cookieEnabled && state.cookieJar != null && state.response != null) {
                    state.cookieJar.saveFromResponse(state.uri, state.response.headers().getAll(HttpHeaderNames.SET_COOKIE));
                }
                RedirectPlan redirect = buildRedirectPlan(state);
                if (redirect != null) {
                    tryClose(state.body);
                    if (state.logEnabled) {
                        log.info("HTTP {} {} req={} -> {} location={}",
                                state.currentRequest.method(), state.uri, state.requestLogText,
                                state.response.status().code(), redirect.url);
                    }
                    state.client.executeAsync0(state.request, redirectRequest(state, redirect),
                            state.config, state.future, state.startNanos, state.redirectCount + 1);
                    return;
                }
                long elapsedNanos = System.nanoTime() - state.startNanos;
                String responseUrl = state.redirectCount == 0 ? state.request.url() : state.uri.toString();
                if (state.cache != null && state.cacheEntry != null && state.response != null
                        && state.response.status().code() == HttpResponseStatus.NOT_MODIFIED.code()) {
                    tryClose(state.body);
                    state.cache.revalidate(state.cacheEntry, state.response);
                    Response cached = state.cache.createCachedResponse(state.client, state.request, responseUrl, state.cacheEntry, state.response, elapsedNanos);
                    if (cached != null) {
                        completeResponse(state, cached, elapsedNanos, true);
                        return;
                    }
                }
                if (state.cache != null && state.cacheStoreAllowed && state.cache.storeable(responseUrl, state.response)) {
                    state.cache.store(responseUrl, state.response, state.body);
                }
                Response content = new Response(state.client, state.request, responseUrl, state.response, state.body, elapsedNanos);
                completeResponse(state, content, elapsedNanos, false);
            } catch (Throwable e) {
                tryClose(state.body);
                FAILED_UPDATER.incrementAndGet(state.client);
                state.future.completeExceptionally(e);
            }
        }

        private void completeResponse(RequestState state, Response content, long elapsedNanos, boolean cacheHit) {
            state.client.activeResponses.add(content);
            SUCCESS_UPDATER.incrementAndGet(state.client);
            TOTAL_LATENCY_UPDATER.addAndGet(state.client, elapsedNanos);
            for (; ; ) {
                long old = state.client.metricMaxLatencyNanos;
                if (elapsedNanos <= old || MAX_LATENCY_UPDATER.compareAndSet(state.client, old, elapsedNanos)) {
                    break;
                }
            }
            if (state.logEnabled) {
                log.info("HTTP {} {} -> {} {}{}\nreq={}\nres={}",
                        state.currentRequest.method(), state.request.url(),
                        content.code(), Sys.formatNanosElapsed(elapsedNanos), cacheHit ? " cache-hit" : Strings.EMPTY,
                        state.requestLogText, responseLogText(content));
            }
            state.future.complete(content);
        }

        private RedirectPlan buildRedirectPlan(RequestState state) {
            HttpResponse response = state.response;
            if (response == null || !state.followRedirects || state.redirectCount >= state.maxRedirects) {
                return null;
            }
            String location = response.headers().get(HttpHeaderNames.LOCATION);
            if (Strings.isEmpty(location)) {
                return null;
            }
            HttpMethod redirectMethod = redirectMethod(response.status().code(), state.currentRequest.method());
            if (redirectMethod == null) {
                return null;
            }
            URI redirectUri;
            try {
                redirectUri = state.uri.resolve(location);
            } catch (Throwable e) {
                return null;
            }
            if (redirectUri == null || Strings.isEmpty(redirectUri.getScheme()) || Strings.isEmpty(redirectUri.getHost())) {
                return null;
            }
            HttpHeaders redirectHeaders = mutableHeaders(state.requestHeaders);
            redirectHeaders.remove(HttpHeaderNames.HOST);
            redirectHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
            redirectHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
            redirectHeaders.remove(HttpHeaderNames.COOKIE);
            if (!sameAuthority(state.uri, redirectUri)) {
                redirectHeaders.remove(HttpHeaderNames.AUTHORIZATION);
                redirectHeaders.remove(HttpHeaderNames.PROXY_AUTHORIZATION);
            }
            if (HttpMethod.GET.equals(redirectMethod) || HttpMethod.HEAD.equals(redirectMethod)) {
                redirectHeaders.remove(HttpHeaderNames.CONTENT_TYPE);
            }
            return new RedirectPlan(redirectUri.toString(), redirectMethod, redirectHeaders);
        }

        private Request redirectRequest(RequestState state, RedirectPlan redirect) {
            Request request = new Request(redirect.method, redirect.url);
            request.body = RequestContent.EMPTY;
            request.timeoutMillis = state.currentRequest.timeoutMillis;
            request.proxy = state.currentRequest.proxy;
            request.enableCookie = state.currentRequest.enableCookie;
            request.followRedirects = state.currentRequest.followRedirects;
            request.maxRedirects = state.currentRequest.maxRedirects;
            request.enableLog = state.currentRequest.enableLog;
            request.headers = redirect.headers;
            return request;
        }

        private HttpMethod redirectMethod(int statusCode, HttpMethod currentMethod) {
            switch (statusCode) {
                case 301:
                case 302:
                    return HttpMethod.GET.equals(currentMethod) || HttpMethod.HEAD.equals(currentMethod) ? currentMethod : HttpMethod.GET;
                case 303:
                    return HttpMethod.HEAD.equals(currentMethod) ? HttpMethod.HEAD : HttpMethod.GET;
                case 307:
                case 308:
                    return HttpMethod.GET.equals(currentMethod) || HttpMethod.HEAD.equals(currentMethod) ? currentMethod : null;
                default:
                    return null;
            }
        }

        private boolean sameAuthority(URI left, URI right) {
            return Strings.equalsIgnoreCase(left.getScheme(), right.getScheme())
                    && Strings.equalsIgnoreCase(left.getHost(), right.getHost())
                    && defaultPort(left) == defaultPort(right);
        }

        private int defaultPort(URI uri) {
            int port = uri.getPort();
            if (port > 0) {
                return port;
            }
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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

    public static class Response implements AutoCloseable {
        final HttpClient owner;
        final Request request;
        final String url;
        final HttpResponse response;
        final HybridStream body;
        final long elapsedNanos;
        String str;
        File file;

        Response(HttpClient owner, Request request, String url, HttpResponse response, HybridStream body, long elapsedNanos) {
            this.owner = owner;
            this.request = Objects.requireNonNull(request, "request");
            this.url = Objects.requireNonNull(url, "url");
            this.response = Objects.requireNonNull(response, "response");
            this.body = Objects.requireNonNull(body, "body");
            this.elapsedNanos = elapsedNanos;
        }

        public Request request() {
            return request;
        }

        public String url() {
            return url;
        }

        public HttpResponseStatus status() {
            return response.status();
        }

        public HttpHeaders headers() {
            return response.headers();
        }

        public int code() {
            return response.status().code();
        }

        public String header(String name) {
            return headers().get(name);
        }

        public Charset charset() {
            String contentType = headers().get(HttpHeaderNames.CONTENT_TYPE);
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

        public HybridStream bodyStream() {
            return body.rewind();
        }

        @SneakyThrows
        public File bodyAsFile(String filePath) {
            if (file == null) {
                Files.saveFile(filePath, body.rewind());
                file = new File(filePath);
            }
            return file;
        }

        public <T extends Serializable> T bodyAsJson() {
            return (T) JSON.parse(bodyAsString());
        }

        public String bodyAsString() {
            if (str == null) {
                str = DuplexStream.readString(body.rewind(), charset());
            }
            return str;
        }

        @Override
        public void close() {
            if (owner != null) {
                owner.activeResponses.remove(this);
            }
            tryClose(body);
        }
    }

    public Response head(@NonNull String url) {
        return execute(url, HttpMethod.HEAD, RequestContent.EMPTY);
    }

    public Response get(@NonNull String url) {
        return execute(url, HttpMethod.GET, RequestContent.EMPTY);
    }

    public Response post(String url, Map<String, Object> forms) {
        return post(url, forms, Collections.emptyMap());
    }

    public Response post(@NonNull String url, Map<String, Object> forms, Map<String, DuplexStream> files) {
        if (MapUtils.isEmpty(files)) {
            return execute(url, HttpMethod.POST, new FormContent(forms));
        }
        return execute(url, HttpMethod.POST, new MultipartContent(forms, files));
    }

    public Response postJson(@NonNull String url, @NonNull Object json) {
        return execute(url, HttpMethod.POST, new JsonContent(json));
    }

    public Response put(String url, Map<String, Object> forms) {
        return put(url, forms, Collections.emptyMap());
    }

    public Response put(@NonNull String url, Map<String, Object> forms, Map<String, DuplexStream> files) {
        if (MapUtils.isEmpty(files)) {
            return execute(url, HttpMethod.PUT, new FormContent(forms));
        }
        return execute(url, HttpMethod.PUT, new MultipartContent(forms, files));
    }

    public Response putJson(@NonNull String url, @NonNull Object json) {
        return execute(url, HttpMethod.PUT, new JsonContent(json));
    }

    public Response patch(String url, Map<String, Object> forms) {
        return patch(url, forms, Collections.emptyMap());
    }

    public Response patch(@NonNull String url, Map<String, Object> forms, Map<String, DuplexStream> files) {
        if (MapUtils.isEmpty(files)) {
            return execute(url, HttpMethod.PATCH, new FormContent(forms));
        }
        return execute(url, HttpMethod.PATCH, new MultipartContent(forms, files));
    }

    public Response patchJson(@NonNull String url, @NonNull Object json) {
        return execute(url, HttpMethod.PATCH, new JsonContent(json));
    }

    public Response delete(String url, Map<String, Object> forms) {
        return delete(url, forms, Collections.emptyMap());
    }

    public Response delete(@NonNull String url, Map<String, Object> forms, Map<String, DuplexStream> files) {
        if (MapUtils.isEmpty(files)) {
            return execute(url, HttpMethod.DELETE, new FormContent(forms));
        }
        return execute(url, HttpMethod.DELETE, new MultipartContent(forms, files));
    }

    public Response deleteJson(@NonNull String url, @NonNull Object json) {
        return execute(url, HttpMethod.DELETE, new JsonContent(json));
    }

    @SneakyThrows
    public Response execute(Request request) {
        Objects.requireNonNull(request, "request");
        ensureBlockingCallAllowed();
        HttpClientConfig cfg = config;
        CompletableFuture<Response> future = executeAsync(request, cfg);
        try {
            return future.get(callTimeoutMillis(cfg, request.timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    public CompletableFuture<Response> executeAsync(Request request) {
        return executeAsync(request, config);
    }

    CompletableFuture<Response> executeAsync(Request request, HttpClientConfig cfg) {
        if (request == null) {
            CompletableFuture<Response> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("request is null"));
            return future;
        }
        Request snapshot = snapshotRequest(request, cfg);
        CompletableFuture<Response> future = new CompletableFuture<>();
        executeAsync0(snapshot, snapshot, cfg, future, System.nanoTime(), 0);
        return future;
    }

    public Tuple<RequestContent, Response> forward(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String forwardUrl) {
        return forward(servletRequest, servletResponse, forwardUrl, null);
    }

    @SneakyThrows
    public Tuple<RequestContent, Response> forward(HttpServletRequest servletRequest, HttpServletResponse servletResponse, String forwardUrl,
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
            content = RequestContent.EMPTY;
        } else {
            content = new StreamContent(servletRequest.getContentType(), servletRequest.getInputStream());
        }
        if (requestInterceptor != null) {
            RequestContent intercepted = requestInterceptor.invoke(content);
            if (intercepted != null) {
                content = intercepted;
            }
        }

        Response response = execute(forwardUrl, method, content, headers);
        servletResponse.setStatus(response.code());
        for (Map.Entry<String, String> header : response.headers()) {
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
        String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (!Strings.isEmpty(contentType)) {
            servletResponse.setContentType(contentType);
        }
        ServletOutputStream out = servletResponse.getOutputStream();
        DuplexStream.copy(response.bodyStream(), DuplexStream.NON_READ_FULLY, out);
        return Tuple.of(content, response);
    }

    private Long responseLength(Response response) {
        String value = response.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        if (!Strings.isEmpty(value)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return response.body.getLength();
        } catch (Throwable e) {
            return null;
        }
    }

    @SneakyThrows
    Response execute(String url, HttpMethod method, RequestContent content) {
        return execute(request(method, url).body(content));
    }

    @SneakyThrows
    Response execute(String url, HttpMethod method, RequestContent content, HttpHeaders requestHeaders) {
        Request request = new Request(method, url);
        request.body = content != null ? content : RequestContent.EMPTY;
        request.headers = requestHeaders;
        return execute(request);
    }

    private void completeCacheHit(Request request, Request currentRequest, URI uri, CompletableFuture<Response> future,
                                  HttpClientCache cache, HttpClientCache.CacheEntry cacheEntry, long startNanos, int redirectCount) {
        if (future.isDone()) {
            return;
        }
        long elapsedNanos = System.nanoTime() - startNanos;
        String responseUrl = redirectCount == 0 ? request.url() : uri.toString();
        Response response = cache.createCachedResponse(this, request, responseUrl, cacheEntry, null, elapsedNanos);
        if (response == null) {
            return;
        }
        activeResponses.add(response);
        SUCCESS_UPDATER.incrementAndGet(this);
        TOTAL_LATENCY_UPDATER.addAndGet(this, elapsedNanos);
        for (; ; ) {
            long old = metricMaxLatencyNanos;
            if (elapsedNanos <= old || MAX_LATENCY_UPDATER.compareAndSet(this, old, elapsedNanos)) {
                break;
            }
        }
        if (Boolean.TRUE.equals(currentRequest.enableLog)) {
            log.info("HTTP {} {} -> {} {} cache-hit\nreq={}\nres={}",
                    currentRequest.method(), request.url(),
                    response.code(), Sys.formatNanosElapsed(elapsedNanos),
                    requestLogText(currentRequest), responseLogText(response));
        }
        future.complete(response);
    }

    void executeAsync0(Request request, Request currentRequest, HttpClientConfig cfg,
                       CompletableFuture<Response> future, long startNanos, int redirectCount) {
        if (redirectCount == 0) {
            REQUESTS_UPDATER.incrementAndGet(this);
        }
        String url = currentRequest.url;
        Proxy requestProxy = currentRequest.proxy;
        RequestContent content = currentRequest.body;
        boolean cookieEnabled = currentRequest.enableCookie != null && currentRequest.enableCookie;
        int timeoutMillis = currentRequest.timeoutMillis;
        HttpHeaders requestHeaders = currentRequest.headers;
        URI uri;
        try {
            uri = URI.create(url);
            if (Strings.isEmpty(uri.getScheme()) || Strings.isEmpty(uri.getHost())) {
                throw new IllegalArgumentException("Invalid http url " + url);
            }
        } catch (Throwable e) {
            FAILED_UPDATER.incrementAndGet(this);
            future.completeExceptionally(e);
            tryClose(content);
            return;
        }
        HttpClientCache cache = cfg.getCache();
        HttpClientCache.CacheEntry cacheEntry = null;
        boolean cacheStoreAllowed = cacheStoreAllowed(currentRequest);
        if (cache != null && cacheLookupAllowed(currentRequest, cookieEnabled) && !cacheBypass(requestHeaders)) {
            cacheEntry = cache.get(uri.toString());
            if (cacheEntry != null) {
                boolean forceRevalidate = forceCacheRevalidate(requestHeaders);
                if (!forceRevalidate && cacheEntry.isFresh(System.currentTimeMillis())) {
                    completeCacheHit(request, currentRequest, uri, future, cache, cacheEntry, startNanos, redirectCount);
                    tryClose(content);
                    return;
                }
                if (cacheEntry.canRevalidate()) {
                    cache.applyValidators(ensureMutableHeaders(currentRequest), cacheEntry);
                    requestHeaders = currentRequest.headers;
                } else if (forceRevalidate) {
                    cacheEntry = null;
                }
            }
        } else {
            cacheStoreAllowed = false;
        }
        PoolKey key = new PoolKey(uri, requestProxy);
        FixedChannelPool pool = pools.computeIfAbsent(key, this::newPool);
        AtomicReference<Channel> acquiredChannel = new AtomicReference<>();
        int requestTimeout = timeoutMillis;
        final Request requestSnapshot = currentRequest;
        final HttpHeaders requestHeadersSnapshot = requestHeaders;
        final HttpClientCache.CacheEntry requestCacheEntry = cacheEntry;
        final boolean requestCacheStoreAllowed = cacheStoreAllowed;

        pool.acquire().addListener((io.netty.util.concurrent.Future<Channel> f) -> {
            if (!f.isSuccess()) {
                FAILED_UPDATER.incrementAndGet(this);
                future.completeExceptionally(f.cause());
                tryClose(content);
                return;
            }
            Channel channel = f.getNow();
            acquiredChannel.set(channel);
            if (future.isDone()) {
                tryClose(content);
                release(channel, pool, true);
                return;
            }
            RequestState state = new RequestState(request, requestSnapshot, uri, key, pool, future, cfg, cfg.getCookieJar(), cache, requestCacheEntry, requestProxy, redirectCount, requestTimeout,
                    requestHeadersSnapshot, content, this, cfg.getResponseOffloadThreshold(), cfg.getUploadFlushBytes(), cfg.getUploadFlushChunks(),
                    startNanos, cookieEnabled, requestCacheStoreAllowed, Boolean.TRUE.equals(requestSnapshot.followRedirects), requestSnapshot.maxRedirects != null ? requestSnapshot.maxRedirects : 0,
                    Boolean.TRUE.equals(requestSnapshot.enableLog));
            channel.attr(STATE_KEY).set(state);
            state.timeoutFuture = channel.eventLoop().schedule(() -> {
                TIMEOUT_UPDATER.incrementAndGet(this);
                fail(channel, new TimeoutException("HTTP request timeout " + requestTimeout + "ms: " + url));
            }, requestTimeout, TimeUnit.MILLISECONDS);
            try {
                writeRequest(channel, uri, requestSnapshot.method, content, state);
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
                RequestState state = channel.attr(STATE_KEY).get();
                if (state == null || state.future != future) {
                    return;
                }
                fail(channel, new TimeoutException("HTTP request cancelled: " + url));
            }
        });
    }

    private void ensureBlockingCallAllowed() {
        Thread current = Thread.currentThread();
        for (EventExecutor executor : Sockets.reactor(Sockets.ReactorNames.SHARED_TCP, true)) {
            if (executor.inEventLoop(current)) {
                throw new IllegalStateException("HttpClient sync API cannot be called from Netty EventLoop; use executeAsync instead");
            }
        }
    }

    private int callTimeoutMillis(HttpClientConfig cfg, int requestTimeoutMillis) {
        int rw = requestTimeoutMillis > 0 ? requestTimeoutMillis : cfg.getReadWriteTimeoutMillis();
        return Math.max(cfg.getConnectTimeoutMillis() + rw, rw + (rw >>> 1));
    }

    private FixedChannelPool newPool(PoolKey key) {
        HttpClientConfig cfg = config;
        SocketConfig socketConfig = new SocketConfig();
        socketConfig.setConnectTimeoutMillis(cfg.getConnectTimeoutMillis());
        Bootstrap bootstrap = Sockets.bootstrap(socketConfig, key.unresolvedAddress(), null)
                .remoteAddress(key.unresolvedAddress());
        if (key.proxyType != Proxy.Type.DIRECT) {
            // 保持目标地址 unresolved，让 HTTP CONNECT / SOCKS 交给代理端解析。
            bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
        }
        return new FixedChannelPool(bootstrap, new AbstractChannelPoolHandler() {
            @Override
            public void channelCreated(Channel ch) throws Exception {
                initChannel(ch, key, cfg);
            }
        }, ChannelHealthChecker.ACTIVE, FixedChannelPool.AcquireTimeoutAction.FAIL,
                cfg.getAcquireTimeoutMillis(), cfg.getMaxConnectionsPerHost(), cfg.getMaxPendingAcquires(), true, true);
    }

    private void initChannel(Channel ch, PoolKey key, HttpClientConfig cfg) throws Exception {
        ChannelPipeline p = ch.pipeline();
        ProxyHandler proxyHandler = newProxyHandler(key);
        if (proxyHandler != null) {
            proxyHandler.setConnectTimeoutMillis(cfg.getConnectTimeoutMillis());
            p.addLast(proxyHandler);
        }
        if (key.isHttps()) {
            p.addLast(cfg.getSslContext().newHandler(ch.alloc(), key.host, key.port));
        }
        DiagnosticMetrics.installNetIoHandler(p, DiagnosticMetrics.NET_HTTP_CLIENT);
        p.addLast(new ReadTimeoutHandler(cfg.getReadWriteTimeoutMillis(), TimeUnit.MILLISECONDS),
                new WriteTimeoutHandler(cfg.getReadWriteTimeoutMillis(), TimeUnit.MILLISECONDS),
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
        if (state.cookieEnabled && state.cookieJar != null) {
            String cookie = state.cookieJar.loadForRequest(uri);
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
            if (body.readableBytes() > 0) {
                UPLOAD_BYTES_UPDATER.addAndGet(state.client, body.readableBytes());
            }
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
        tryClose(state.body);
        FAILED_UPDATER.incrementAndGet(state.client);
        if (state.logEnabled) {
            log.warn("HTTP {} {} req={} fail {}",
                    state.currentRequest.method(), state.uri, state.requestLogText, throwableSummary(cause));
        }
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
        return buildUrl(url, queryString, true);
    }

    public static String buildUrl(String url, Map<String, Object> queryString, boolean includeLeadingQuestionMark) {
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
        boolean first = true;
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            Object val = entry.getValue();
            if (val == null) {
                continue;
            }
            if (first) {
                if (includeLeadingQuestionMark) {
                    sb.append("?");
                }
                first = false;
            } else {
                sb.append("&");
            }
            sb.append(encodeUrl(entry.getKey())).append("=").append(encodeUrl(val.toString()));
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
        if (i == -1) {
            return params;
        }
        url = url.substring(i + 1);
        if (Strings.isEmpty(url)) {
            return params;
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
        HttpClientCookieJar.DEFAULT.saveRawCookie(URI.create(url), cookie);
    }
}
