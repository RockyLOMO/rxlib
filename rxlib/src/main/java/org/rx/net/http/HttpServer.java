package org.rx.net.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.MultiValueMap;
import org.rx.core.Arrays;
import org.rx.core.Disposable;
import org.rx.core.RxConfig;
import org.rx.core.Strings;
import org.rx.diagnostic.DiagnosticHttpHandler;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.io.Bytes;
import org.rx.io.DuplexStream;
import org.rx.net.Sockets;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLHandshakeException;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static org.rx.core.Extends.ifNull;

@Slf4j
public class HttpServer extends Disposable {
    static final String ASYNC_HANDLER_HEADER = "X-Http-Async-Handler";
    static volatile HttpServer DEFAULT;

    static final class QuotaExceededException extends IllegalArgumentException {
    }

    static final class RequestState {
        private static final AtomicIntegerFieldUpdater<RequestState> CLEANED =
                AtomicIntegerFieldUpdater.newUpdater(RequestState.class, "cleaned");
        private static final AtomicIntegerFieldUpdater<RequestState> WORKER_PHASE =
                AtomicIntegerFieldUpdater.newUpdater(RequestState.class, "workerPhase");
        final HttpServer owner;
        private volatile int cleaned;
        private volatile int workerPhase;
        HttpRequest request;
        HttpPostRequestDecoder decoder;
        Mapping mapping;
        ServerRequest req;
        long bodyBytes;
        final long startNanos = System.nanoTime();
        boolean multipart;
        boolean uploadPermit;
        boolean asyncPermit;
        boolean receiving = true;
        volatile boolean workerRunning;
        volatile boolean aborted;

        RequestState(HttpServer owner) {
            this.owner = owner;
            owner.metrics.requests.incrementAndGet();
            owner.metrics.activeRequests.incrementAndGet();
        }

        void cleanupOnce() {
            if (!CLEANED.compareAndSet(this, 0, 1)) {
                return;
            }
            try {
                if (req != null) {
                    Bytes.release(req.getContent());
                    req.setContent(null);
                }
                if (decoder != null) {
                    // next() returns borrowed data. destroy() owns the factory data,
                    // completed parts and undecoded content in Netty 4.2.12.
                    decoder.destroy();
                }
            } finally {
                if (uploadPermit) {
                    owner.activeUploads.decrementAndGet();
                }
                if (asyncPermit) {
                    owner.activeAsync.decrementAndGet();
                }
                owner.metrics.activeRequests.decrementAndGet();
            }
        }

        void abort() {
            aborted = true;
            // A queued callback has not borrowed the request yet. Cancel its claim
            // so even an executor that never runs it cannot hold native resources.
            if (WORKER_PHASE.compareAndSet(this, 1, 3)) {
                workerRunning = false;
            }
            if (!workerRunning) {
                cleanupOnce();
            }
        }
    }

    public static final class Mapping {
        final Handler handler;
        final boolean async;

        Mapping(Handler handler, boolean async) {
            this.handler = handler;
            this.async = async;
        }
    }

    class ServerHandler extends SimpleChannelInboundHandler<HttpObject> {
        RequestState state;
        boolean closing;
        boolean connectionCounted;
        boolean headerStarted;
        ScheduledFuture<?> deadline;
        long deadlineNanos;
        long scheduledDeadlineNanos;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            connectionCounted = true;
            long connections = metrics.activeConnections.incrementAndGet();
            children.add(ctx.channel());
            if (!ctx.channel().isActive()) {
                return;
            }
            if (connections > config.getMaxConnections()) {
                metrics.rejected.incrementAndGet();
                closing = true;
                ctx.close();
                return;
            }
            headerStarted = true;
            armTimeout(ctx, config.getHeaderTimeoutMillis());
            super.channelActive(ctx);
        }

        void networkRead(ChannelHandlerContext ctx) {
            if (!closing && state == null && !headerStarted) {
                headerStarted = true;
                armTimeout(ctx, config.getHeaderTimeoutMillis());
            }
        }

        void armTimeout(ChannelHandlerContext ctx, int millis) {
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            if (deadline == null || deadlineNanos < scheduledDeadlineNanos) {
                if (deadline != null) {
                    deadline.cancel(false);
                }
                scheduledDeadlineNanos = deadlineNanos;
                deadline = ctx.executor().schedule(() -> checkTimeout(ctx), millis, TimeUnit.MILLISECONDS);
            }
        }

        void checkTimeout(ChannelHandlerContext ctx) {
            deadline = null;
            if (closing) {
                return;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0) {
                scheduledDeadlineNanos = deadlineNanos;
                deadline = ctx.executor().schedule(() -> checkTimeout(ctx), remaining, TimeUnit.NANOSECONDS);
                return;
            }
            if (state == null && !headerStarted) {
                closing = true;
                ctx.close();
                return;
            }
            metrics.timedOut.incrementAndGet();
            reject(ctx, state == null ? new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/") : state.request,
                    REQUEST_TIMEOUT);
        }

        void cancelTimeout() {
            if (deadline != null) {
                deadline.cancel(false);
                deadline = null;
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
            if (closing) {
                return;
            }
            if (!msg.decoderResult().isSuccess()) {
                Throwable cause = msg.decoderResult().cause();
                HttpResponseStatus status = cause instanceof TooLongHttpLineException ? REQUEST_URI_TOO_LONG
                        : cause instanceof TooLongHttpHeaderException ? REQUEST_HEADER_FIELDS_TOO_LARGE : BAD_REQUEST;
                reject(ctx, msg instanceof HttpRequest ? (HttpRequest) msg : state == null
                        ? new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/") : state.request, status);
                return;
            }
            if (msg instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) msg;
                if (state != null) {
                    // autoRead(false) cannot retract messages already decoded in this read.
                    // Deliberately reject pipelining rather than overwrite an in-flight request.
                    metrics.rejected.incrementAndGet();
                    abort(ctx);
                    return;
                }
                if (request.uri().length() > config.getMaxUriLength()) {
                    reject(ctx, request, REQUEST_URI_TOO_LONG);
                    return;
                }
                QueryStringDecoder query;
                try {
                    query = new QueryStringDecoder(request.uri());
                    query.parameters();
                } catch (IllegalArgumentException e) {
                    reject(ctx, request, BAD_REQUEST);
                    return;
                }
                Mapping route = mapping.get(normalize(query.path()));
                if (route == null) {
                    reject(ctx, request, NOT_FOUND);
                    return;
                }
                HttpMethod[] methods = route.handler.method();
                if (methods != null && !Arrays.contains(methods, request.method())) {
                    reject(ctx, request, METHOD_NOT_ALLOWED);
                    return;
                }
                RequestState current = new RequestState(HttpServer.this);
                state = current;
                current.request = request;
                current.mapping = route;
                current.req = new ServerRequest((InetSocketAddress) ctx.channel().remoteAddress(), request.uri(), request.method());
                current.req.getHeaders().setAll(request.headers());
                for (Map.Entry<String, List<String>> entry : query.parameters().entrySet()) {
                    for (String value : entry.getValue()) {
                        current.req.getQueryString().put(entry.getKey(), value);
                    }
                }
                CharSequence type = HttpUtil.getMimeType(request);
                current.multipart = HttpHeaderValues.MULTIPART_FORM_DATA.contentEqualsIgnoreCase(type);
                long limit = current.multipart ? config.getMaxMultipartBytes() : config.getMaxRequestBodyBytes();
                long length;
                try {
                    length = HttpUtil.getContentLength(request, -1L);
                } catch (NumberFormatException e) {
                    reject(ctx, request, BAD_REQUEST);
                    return;
                }
                if (length < -1 || request.headers().contains(HttpHeaderNames.CONTENT_LENGTH) && length < 0 || length > limit) {
                    reject(ctx, request, length < 0 ? BAD_REQUEST : REQUEST_ENTITY_TOO_LARGE);
                    return;
                }
                if (request.headers().contains(HttpHeaderNames.EXPECT) && !HttpUtil.is100ContinueExpected(request)) {
                    reject(ctx, request, EXPECTATION_FAILED);
                    return;
                }
                if (current.multipart) {
                    if (activeUploads.incrementAndGet() > config.getMaxConcurrentUploads()) {
                        activeUploads.decrementAndGet();
                        reject(ctx, request, SERVICE_UNAVAILABLE);
                        return;
                    }
                    current.uploadPermit = true;
                }
                if (current.multipart && !HttpPostRequestDecoder.isMultipart(request)) {
                    reject(ctx, request, BAD_REQUEST);
                    return;
                }
                if (current.multipart || HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.contentEqualsIgnoreCase(type)) {
                    try {
                        current.decoder = new HttpPostRequestDecoder(factory, request, CharsetUtil.UTF_8,
                                config.getMaxFormFields(), config.getMaxFormFieldBytes());
                    } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
                        reject(ctx, request, BAD_REQUEST);
                        return;
                    }
                }
                if (HttpUtil.is100ContinueExpected(request)) {
                    ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, CONTINUE, Unpooled.EMPTY_BUFFER));
                }
                armTimeout(ctx, config.getBodyTimeoutMillis());
            }
            if (!(msg instanceof HttpContent) || state == null) {
                return;
            }
            RequestState current = state;
            if (!current.receiving) {
                abort(ctx);
                return;
            }
            HttpContent content = (HttpContent) msg;
            int bytes = content.content().readableBytes();
            long limit = current.multipart ? config.getMaxMultipartBytes() : config.getMaxRequestBodyBytes();
            if (bytes > limit - current.bodyBytes) {
                reject(ctx, current.request, REQUEST_ENTITY_TOO_LARGE);
                return;
            }
            current.bodyBytes += bytes;
            metrics.bodyBytesIn.addAndGet(bytes);
            if (bytes > 0) {
                armTimeout(ctx, config.getBodyTimeoutMillis());
            }
            if (current.decoder != null) {
                try {
                    current.decoder.offer(content);
                    InterfaceHttpData partial = current.decoder.currentPartialHttpData();
                    checkPart(partial);
                    while (current.decoder.hasNext()) {
                        InterfaceHttpData data = current.decoder.next();
                        // The decoder's completed-part list owns this reference. Remove
                        // the factory cleanup alias so an application retain survives
                        // destroy() (factory cleanup and bodyList otherwise both release).
                        current.decoder.removeHttpDataFromClean(data);
                        checkPart(data);
                        if (data instanceof Attribute) {
                            Attribute attr = (Attribute) data;
                            MultiValueMap<String, String> form = current.req.getForm();
                            if (form == null) {
                                current.req.setForm(form = new MultiValueMap<>());
                            }
                            form.put(attr.getName(), attr.getValue());
                        } else if (data instanceof FileUpload) {
                            FileUpload file = (FileUpload) data;
                            MultiValueMap<String, FileUpload> files = current.req.getFiles();
                            if (files == null) {
                                current.req.setFiles(files = new MultiValueMap<>());
                            }
                            files.put(file.getName(), file);
                        }
                    }
                } catch (HttpPostRequestDecoder.EndOfDataDecoderException expected) {
                    // End of decoded parts.
                } catch (HttpPostRequestDecoder.TooManyFormFieldsException | HttpPostRequestDecoder.TooLongFormFieldException e) {
                    reject(ctx, current.request, REQUEST_ENTITY_TOO_LARGE);
                    return;
                } catch (QuotaExceededException e) {
                    reject(ctx, current.request, REQUEST_ENTITY_TOO_LARGE);
                    return;
                } catch (IllegalArgumentException e) {
                    reject(ctx, current.request, BAD_REQUEST);
                    return;
                } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
                    reject(ctx, current.request, BAD_REQUEST);
                    return;
                }
            } else {
                ByteBuf buffer = current.req.getContent();
                if (buffer == null) {
                    // The quota was checked before allocating or growing the body buffer.
                    current.req.setContent(buffer = ctx.alloc().heapBuffer(Math.min(4096, bytes),
                            (int) Math.min(Integer.MAX_VALUE, config.getMaxRequestBodyBytes())));
                }
                buffer.writeBytes(content.content());
            }
            if (msg instanceof LastHttpContent) {
                cancelTimeout();
                current.receiving = false;
                ctx.channel().config().setAutoRead(false);
                dispatch(ctx, current);
            }
        }

        void checkPart(InterfaceHttpData data) {
            if (data instanceof HttpData) {
                long limit = data instanceof FileUpload ? config.getMaxFileBytes() : config.getMaxFormFieldBytes();
                if (((HttpData) data).length() > limit) {
                    throw new QuotaExceededException();
                }
            }
        }

        void dispatch(ChannelHandlerContext ctx, RequestState current) {
            if (!current.mapping.async) {
                FullHttpResponse response = handle(current);
                sendResponse(ctx, current, response, false);
                return;
            }
            if (activeAsync.incrementAndGet() > config.getMaxAsyncRequests()) {
                activeAsync.decrementAndGet();
                reject(ctx, current.request, SERVICE_UNAVAILABLE);
                return;
            }
            current.asyncPermit = true;
            current.workerRunning = true;
            current.workerPhase = 1;
            try {
                businessExecutor.execute(() -> {
                    if (!RequestState.WORKER_PHASE.compareAndSet(current, 1, 2)) {
                        return;
                    }
                    FullHttpResponse response;
                    if (current.aborted) {
                        current.workerRunning = false;
                        current.cleanupOnce();
                        return;
                    }
                    response = handle(current);
                    if (current.aborted || !ctx.channel().isActive()) {
                        response.release();
                        return;
                    }
                    try {
                        ctx.executor().execute(() -> sendResponse(ctx, current, response, false));
                    } catch (Throwable e) {
                        response.release();
                        ctx.close();
                    }
                });
            } catch (Throwable e) {
                current.workerRunning = false;
                reject(ctx, current.request, SERVICE_UNAVAILABLE);
            }
        }

        FullHttpResponse handle(RequestState current) {
            try {
                return invokeHandler(current);
            } catch (Throwable e) {
                log.error("HTTP handler failed", e);
                return newErrorResponse(current.request, INTERNAL_SERVER_ERROR);
            } finally {
                current.workerPhase = 3;
                current.workerRunning = false;
                current.cleanupOnce();
            }
        }

        FullHttpResponse invokeHandler(RequestState current) throws Throwable {
            ServerResponse result = new ServerResponse();
            try {
                current.mapping.handler.handle(current.req, result);
                HttpResponseStatus status = ifNull(result.getStatus(), OK);
                if (status.code() < 200) {
                    throw new IllegalArgumentException("Informational status cannot be a final handler response");
                }
                ByteBuf body = result.takeContent();
                FullHttpResponse response = null;
                try {
                    int length = body == null ? 0 : body.readableBytes();
                    boolean head = HttpMethod.HEAD.equals(current.request.method());
                    int code = status.code();
                    boolean forbidden = code < 200 || code == 204 || code == 205 || code == 304;
                    response = new DefaultFullHttpResponse(current.request.protocolVersion(), status,
                            !head && !forbidden && body != null ? body : Unpooled.EMPTY_BUFFER);
                    if (!head && !forbidden) {
                        body = null; // Netty response owns it now.
                    }
                    response.headers().setAll(result.getHeaders());
                    response.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
                    if (code < 200 || code == 204) {
                        response.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
                    } else if (code == 304) {
                        // Preserve only explicit representation metadata for 304.
                    } else {
                        HttpUtil.setContentLength(response, code == 205 ? 0 : length);
                    }
                    if (current.mapping.async) {
                        response.headers().set(ASYNC_HANDLER_HEADER, "1");
                    }
                    return response;
                } catch (Throwable e) {
                    ReferenceCountUtil.release(response);
                    throw e;
                } finally {
                    Bytes.release(body);
                }
            } finally {
                result.releaseContent();
            }
        }

        FullHttpResponse newErrorResponse(HttpRequest request, HttpResponseStatus status) {
            String message = "Failure: " + status;
            FullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), status,
                    HttpMethod.HEAD.equals(request.method()) ? Unpooled.EMPTY_BUFFER : Unpooled.copiedBuffer(message, CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            HttpUtil.setContentLength(response, message.length());
            return response;
        }

        void reject(ChannelHandlerContext ctx, HttpRequest request, HttpResponseStatus status) {
            metrics.rejected.incrementAndGet();
            closing = true;
            cancelTimeout();
            RequestState current = state;
            if (current != null) {
                current.abort();
            }
            sendResponse(ctx, current, newErrorResponse(request, status), true);
        }

        void sendResponse(ChannelHandlerContext ctx, RequestState current, FullHttpResponse response, boolean forceClose) {
            if (!ctx.channel().isActive() || !forceClose && (closing || current.aborted)) {
                response.release();
                return;
            }
            boolean keepAlive = !forceClose && response.status().code() >= 200 && HttpUtil.isKeepAlive(current.request);
            HttpUtil.setKeepAlive(response, keepAlive);
            metrics.bodyBytesOut.addAndGet(response.content().readableBytes());
            try {
                // A header timeout can occur before the compressor has seen an
                // HttpRequest. Error responses close the connection and bypass it.
                ChannelHandlerContext compressor = forceClose ? ctx.pipeline().context(HttpContentCompressor.class) : null;
                ChannelFuture write = compressor == null ? ctx.writeAndFlush(response) : compressor.writeAndFlush(response);
                write.addListener(f -> {
                    if (!f.isSuccess()) {
                        metrics.writeFailures.incrementAndGet();
                        abort(ctx);
                        return;
                    }
                    if (current != null && !forceClose) {
                        metrics.responses.incrementAndGet();
                        long elapsed = System.nanoTime() - current.startNanos;
                        metrics.totalLatencyNanos.addAndGet(elapsed);
                        metrics.maxLatencyNanos.accumulateAndGet(elapsed, Math::max);
                    }
                    if (!keepAlive) {
                        closing = true;
                        ctx.close();
                        return;
                    }
                    if (state == current) {
                        state = null;
                    }
                    headerStarted = false;
                    armTimeout(ctx, config.getKeepAliveIdleMillis());
                    ctx.channel().config().setAutoRead(true);
                });
            } catch (Throwable e) {
                ReferenceCountUtil.safeRelease(response);
                metrics.writeFailures.incrementAndGet();
                abort(ctx);
            }
        }

        void abort(ChannelHandlerContext ctx) {
            closing = true;
            cancelTimeout();
            RequestState current = state;
            if (current != null) {
                current.abort();
            }
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            abort(ctx);
            state = null;
            if (connectionCounted) {
                connectionCounted = false;
                metrics.activeConnections.decrementAndGet();
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            SSLHandshakeException handshake = findSslHandshakeException(cause);
            if (handshake != null) {
                log.warn("TLS handshake failed from {}: {}", ctx.channel().remoteAddress(), handshake.getMessage());
                abort(ctx);
                return;
            }
            log.debug("HTTP connection failed", cause);
            if (!closing && state != null && state.receiving && ctx.channel().isActive()) {
                reject(ctx, state.request, BAD_REQUEST);
            } else {
                abort(ctx);
            }
        }
    }


    static SSLHandshakeException findSslHandshakeException(Throwable cause) {
        for (int i = 0; cause != null && i < 16; i++) {
            if (cause instanceof SSLHandshakeException) {
                return (SSLHandshakeException) cause;
            }
            cause = cause.getCause();
        }
        return null;
    }

    public interface Handler {
        default HttpMethod[] method() {
            return null;
        }

        /** Request buffers/files are borrowed until this method returns. Retain explicitly to keep them. */
        void handle(ServerRequest request, ServerResponse response) throws Throwable;
    }

    final HttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);

    public static String normalize(String uri) {
        // "/" => "/"
        // "" => "/"
        // "/a/b/" => "/a/b"
        // "a/b/" => "/a/b"
        uri = uri.trim();
        // 删除后缀的/
        while (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        // 删除前缀的/
        while (uri.startsWith("/")) {
            uri = uri.substring(1);
        }
        // 前缀补充/
        if (!uri.startsWith("/")) {
            uri = "/" + uri;
        }
        return uri;
    }

    final ServerBootstrap serverBootstrap;
    final List<Channel> serverChannels;
    final HttpServerConfig config;
    final Executor businessExecutor;
    final ThreadPoolExecutor ownedBusinessExecutor;
    final ChannelGroup children = new DefaultChannelGroup(
            GlobalEventExecutor.INSTANCE, true);
    final AtomicInteger activeUploads = new AtomicInteger();
    final AtomicInteger activeAsync = new AtomicInteger();
    @Getter
    final Metrics metrics = new Metrics(this);

    public static final class Metrics {
        private final HttpServer owner;

        Metrics(HttpServer owner) {
            this.owner = owner;
        }

        final AtomicLong requests = new AtomicLong();
        final AtomicLong responses = new AtomicLong();
        final AtomicLong totalLatencyNanos = new AtomicLong();
        final AtomicLong maxLatencyNanos = new AtomicLong();
        final AtomicLong activeConnections = new AtomicLong();
        final AtomicLong activeRequests = new AtomicLong();
        final AtomicLong bodyBytesIn = new AtomicLong();
        final AtomicLong bodyBytesOut = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong timedOut = new AtomicLong();
        final AtomicLong writeFailures = new AtomicLong();

        public long activeConnections() { return activeConnections.get(); }
        /** Running and queued async handlers, bounded by maxAsyncRequests. */
        public int asyncRequests() { return owner.activeAsync.get(); }
        public int activeUploads() { return owner.activeUploads.get(); }
        public long requests() { return requests.get(); }
        public long responses() { return responses.get(); }
        public long totalLatencyNanos() { return totalLatencyNanos.get(); }
        public long maxLatencyNanos() { return maxLatencyNanos.get(); }
        public long activeRequests() { return activeRequests.get(); }
        public long bodyBytesIn() { return bodyBytesIn.get(); }
        public long bodyBytesOut() { return bodyBytesOut.get(); }
        public long rejected() { return rejected.get(); }
        public long timedOut() { return timedOut.get(); }
        public long writeFailures() { return writeFailures.get(); }
        /** JVM-wide pooled Netty memory, not exclusive to this server. */
        public long usedDirectMemory() { return PooledByteBufAllocator.DEFAULT.metric().usedDirectMemory(); }
    }
    @Getter
    final int port;
    @Getter
    final boolean tls;
    final io.netty.handler.ssl.SslContext sslContext;
    @Getter
    final Map<String, Mapping> mapping = new ConcurrentHashMap<>();

    public HttpServer(int port, boolean tls) {
        this(port, tls, new HttpServerConfig());
    }

    @SneakyThrows
    public HttpServer(int port, boolean tls, HttpServerConfig config) {
        this.config = new HttpServerConfig(java.util.Objects.requireNonNull(config, "config"));
        if (this.config.getBusinessExecutor() == null) {
            ownedBusinessExecutor = new ThreadPoolExecutor(0, this.config.getMaxAsyncRequests(),
                    60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                    new DefaultThreadFactory("http-server-business", true),
                    new ThreadPoolExecutor.AbortPolicy());
            businessExecutor = ownedBusinessExecutor;
        } else {
            ownedBusinessExecutor = null;
            businessExecutor = this.config.getBusinessExecutor();
        }
        this.port = port;
        this.tls = tls;
        if (tls) {
            RxConfig.HttpConfig http = RxConfig.INSTANCE.getNet().getHttp();
            sslContext = Sockets.sslContext(http.getServerCertificatePath(), http.getServerCertificatePassword());
        } else {
            sslContext = null;
        }

        serverBootstrap = Sockets.serverBootstrap(ch -> {
            ChannelPipeline p = ch.pipeline();
            if (tls) {
                p.addLast(sslContext.newHandler(ch.alloc()));
            }
            DiagnosticMetrics.installNetIoHandler(p, DiagnosticMetrics.NET_HTTP_SERVER);
            ServerHandler handler = new ServerHandler();
            p.addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    handler.networkRead(ctx);
                    super.channelRead(ctx, msg);
                }
            });
            int initialLineBytes = (int) Math.min(Integer.MAX_VALUE, (long) this.config.getMaxUriLength() + 32);
            p.addLast(new HttpServerCodec(initialLineBytes, this.config.getMaxHeaderBytes(), 8192),
                    new HttpContentCompressor(),
                    new ChunkedWriteHandler(),
                    handler);
        });
        InetSocketAddress bindAddress = this.config.getBindAddress() == null ? Sockets.newAnyEndpoint(port) : this.config.getBindAddress();
        serverChannels = Sockets.bindChannels(serverBootstrap, bindAddress, null);
    }

    @Override
    protected void dispose() {
        children.close();
        if (ownedBusinessExecutor != null) {
            ownedBusinessExecutor.shutdownNow();
        }
        for (Channel channel : serverChannels) {
            if (channel.isOpen()) {
                channel.close();
            }
        }
        Sockets.closeBootstrap(serverBootstrap);
        synchronized (HttpServer.class) {
            if (DEFAULT == this) {
                DEFAULT = null;
            }
        }
    }

    /** Runs on the EventLoop. Blocking work must use requestAsync. */
    public HttpServer requestMapping(String path, Handler handler) {
        return requestMapping(path, handler, false);
    }

    public HttpServer requestMapping(String path, Handler handler, boolean async) {
        mapping.put(normalize(path), new Mapping(handler, async));
        return this;
    }

    public HttpServer requestAsync(String path, Handler handler) {
        return requestMapping(path, handler, true);
    }

    public HttpServer requestDiagnostic() {
        return requestDiagnostic("/rdiag");
    }

    public HttpServer requestDiagnostic(String path) {
        return requestAsync(path, new DiagnosticHttpHandler());
    }

    public static HttpServer getDefault() {
        HttpServer server = DEFAULT;
        if (server != null) {
            return server;
        }
        RxConfig.HttpConfig http = RxConfig.INSTANCE.getNet().getHttp();
        int port = http.getServerPort();
        if (port <= 0) {
            return null;
        }
        boolean tls = http.isServerTls();
        synchronized (HttpServer.class) {
            server = DEFAULT;
            if (server != null) {
                return server;
            }
            DEFAULT = server = new HttpServer(port, tls);
            server.requestDiagnostic();
            log.info("Default http server bind on port {}", port);
            return server;
        }
    }

    public static String renderTemplate(CharSequence template, Map<String, Object> vars) {
        return Strings.renderTemplate(template, vars);
    }

    @SneakyThrows
    public static String renderHtmlTemplate(String resourcePath, Map<String, Object> vars) {
        String path = resourcePath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        InputStream in = loader != null ? loader.getResourceAsStream(path) : null;
        if (in == null) {
            in = HttpServer.class.getClassLoader().getResourceAsStream(path);
        }
        if (in == null) {
            throw new IllegalArgumentException("Template not found: " + resourcePath);
        }
        try {
            return renderTemplate(DuplexStream.readString(in, StandardCharsets.UTF_8), vars);
        } finally {
            in.close();
        }
    }
}
