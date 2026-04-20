package org.rx.net.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
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
import org.rx.core.Tasks;
import org.rx.core.Strings;
import org.rx.diagnostic.DiagnosticHttpHandler;
import org.rx.io.Bytes;
import org.rx.net.Sockets;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static org.rx.core.Extends.ifNull;

@Slf4j
public class HttpServer extends Disposable {
    static final String BLOCKING_HANDLER_HEADER = "X-Http-Blocking-Handler";
    static volatile HttpServer DEFAULT;

    static final class RequestState {
        HttpRequest request;
        HttpPostRequestDecoder decoder;
        Handler handler;
        ServerRequest req;
    }

    class ServerHandler extends SimpleChannelInboundHandler<HttpObject> {
        RequestState state;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
            if (msg instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) msg;

                if (!request.decoderResult().isSuccess()) {
                    sendError(ctx, request, null, BAD_REQUEST, false);
                    return;
                }
                QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
                Handler handler = mapping.get(queryStringDecoder.path());
                if (handler == null) {
                    sendError(ctx, request, null, NOT_FOUND, false);
                    return;
                }
                HttpMethod[] method = handler.method();
                if (method != null && !Arrays.contains(method, request.method())) {
                    sendError(ctx, request, null, METHOD_NOT_ALLOWED, false);
                    return;
                }

                RequestState state = new RequestState();
                state.request = request;
                state.handler = handler;
                state.req = new ServerRequest((InetSocketAddress) ctx.channel().remoteAddress(), request.uri(), request.method());
                this.state = state;
                ServerRequest req = state.req;
                req.getHeaders().setAll(request.headers());

                Map<String, List<String>> params = queryStringDecoder.parameters();
                if (!params.isEmpty()) {
                    for (Map.Entry<String, List<String>> p : params.entrySet()) {
                        for (String val : p.getValue()) {
                            req.getQueryString().put(p.getKey(), val);
                        }
                    }
                }

                if (Strings.startsWith(req.getContentType(), HttpHeaderValues.MULTIPART_FORM_DATA.toString())
                        || Strings.startsWith(req.getContentType(), HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString())) {
                    try {
                        state.decoder = new HttpPostRequestDecoder(factory, request);
                    } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
                        log.error("post decode", e);
                        sendError(ctx, request, null, BAD_REQUEST, false);
                        return;
                    }
                }
            }

            if (msg instanceof HttpContent) {
                RequestState state = this.state;
                if (state == null) {
                    return;
                }
                ServerRequest req = state.req;
                if (req == null) {
                    return;
                }

                HttpContent content = (HttpContent) msg;
                if (state.decoder != null) {
                    try {
                        state.decoder.offer(content);
                    } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
                        log.error("post decode", e);
                        sendError(ctx, state.request, state.decoder, BAD_REQUEST, false);
                        this.state = null;
                        return;
                    }

                    try {
                        while (state.decoder.hasNext()) {
                            InterfaceHttpData httpData = state.decoder.next();
                            if (httpData != null) {
                                if (httpData.getHttpDataType().equals(InterfaceHttpData.HttpDataType.Attribute)) {
                                    Attribute attr = (Attribute) httpData;
                                    MultiValueMap<String, String> form = req.getForm();
                                    if (form == null) {
                                        req.setForm(form = new MultiValueMap<>());
                                    }
                                    form.put(attr.getName(), attr.getValue());
                                } else if (httpData.getHttpDataType().equals(InterfaceHttpData.HttpDataType.FileUpload)) {
                                    FileUpload file = (FileUpload) httpData;
                                    MultiValueMap<String, FileUpload> files = req.getFiles();
                                    if (files == null) {
                                        req.setFiles(files = new MultiValueMap<>());
                                    }
                                    files.put(file.getName(), file);
                                }
                            }
                        }
                    } catch (HttpPostRequestDecoder.EndOfDataDecoderException e) {
                        log.debug("EndOfData", e);
                    }
                } else {
                    ByteBuf buf = req.getContent();
                    if (buf == null) {
                        req.setContent(buf = Bytes.heapBuffer());
                    }
                    buf.writeBytes(content.content());
                }

                if (msg instanceof LastHttpContent) {
                    this.state = null;
                    if (state.handler.blocking()) {
                        ctx.channel().config().setAutoRead(false);
                        final RequestState current = state;
                        Tasks.run(() -> {
                            FullHttpResponse response = null;
                            try {
                                response = invokeHandler(current);
                            } catch (Throwable e) {
                                log.error("handle", e);
                                response = newErrorResponse(current.request, INTERNAL_SERVER_ERROR);
                            } finally {
                                Bytes.release(current.req.getContent());
                            }
                            final FullHttpResponse finalResponse = response;
                            ctx.executor().execute(() -> sendResponse(ctx, current.request, current.decoder, finalResponse, true));
                        });
                        return;
                    }

                    FullHttpResponse response;
                    try {
                        response = invokeHandler(state);
                    } catch (Throwable e) {
                        log.error("handle", e);
                        sendError(ctx, state.request, state.decoder, INTERNAL_SERVER_ERROR, false);
                        return;
                    } finally {
                        Bytes.release(req.getContent());
                    }
                    sendResponse(ctx, state.request, state.decoder, response, false);
                }
            }
        }

        private FullHttpResponse invokeHandler(RequestState state) throws Throwable {
            ServerResponse res = new ServerResponse();
            state.handler.handle(state.req, res);
            if (res.getHeaders().contains(HttpHeaderNames.LOCATION)) {
                FullHttpResponse response = new DefaultFullHttpResponse(state.request.protocolVersion(), FOUND, Unpooled.EMPTY_BUFFER);
                response.headers().setAll(res.getHeaders());
                return response;
            }
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(state.request.protocolVersion(),
                    ifNull(res.getStatus(), OK), ifNull(res.getContent(), Unpooled.EMPTY_BUFFER));
            response.headers().setAll(res.getHeaders());
            if (state.handler.blocking()) {
                response.headers().set(BLOCKING_HANDLER_HEADER, "1");
            }
            return response;
        }

        private FullHttpResponse newErrorResponse(HttpRequest request, HttpResponseStatus status) {
            FullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), status,
                    Unpooled.copiedBuffer("Failure: " + status, CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            return response;
        }

        private void sendError(ChannelHandlerContext ctx, HttpRequest request, HttpPostRequestDecoder decoder,
                               HttpResponseStatus status, boolean resumeRead) {
            sendResponse(ctx, request, decoder, newErrorResponse(request, status), resumeRead);
        }

        private void sendResponse(ChannelHandlerContext ctx, HttpRequest request, HttpPostRequestDecoder decoder,
                                  FullHttpResponse response, boolean resumeRead) {
            if (!ctx.channel().isActive()) {
                try {
                    response.release();
                } finally {
                    destroyDecoder(decoder);
                }
                return;
            }
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            HttpUtil.setContentLength(response, response.content().readableBytes());
            if (keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            } else {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
            ChannelFuture future = ctx.writeAndFlush(response);
            future.addListener(f -> {
                destroyDecoder(decoder);
                Channel channel = ctx.channel();
                if (resumeRead && channel.isActive()) {
                    channel.config().setAutoRead(true);
                    channel.read();
                }
                if (!keepAlive && channel.isOpen()) {
                    channel.close();
                }
            });
        }

        private void destroyDecoder(HttpPostRequestDecoder decoder) {
            if (decoder != null) {
                decoder.destroy();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            RequestState state = this.state;
            if (state != null && state.decoder != null) {
                state.decoder.cleanFiles();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.error("caught", cause);
            if (ctx.channel().isActive()) {
                RequestState state = this.state;
                if (state != null) {
                    this.state = null;
                    sendError(ctx, state.request, state.decoder, INTERNAL_SERVER_ERROR, false);
                } else {
                    ctx.close();
                }
            }
        }
    }

    public interface Handler {
        default HttpMethod[] method() {
            return null;
        }

        default boolean blocking() {
            return false;
        }

        void handle(ServerRequest request, ServerResponse response) throws Throwable;
    }

    public static Handler blocking(Handler handler) {
        return new Handler() {
            @Override
            public HttpMethod[] method() {
                return handler.method();
            }

            @Override
            public boolean blocking() {
                return true;
            }

            @Override
            public void handle(ServerRequest request, ServerResponse response) throws Throwable {
                handler.handle(request, response);
            }
        };
    }

    static final HttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE); // Disk if size exceed

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
    @Getter
    final int port;
    @Getter
    final boolean tls;
    @Getter
    final Map<String, Handler> mapping = new ConcurrentHashMap<>();

    @SneakyThrows
    public HttpServer(int port, boolean tls) {
        this.port = port;
        this.tls = tls;
        serverBootstrap = Sockets.serverBootstrap(ch -> {
            ChannelPipeline p = ch.pipeline();
            if (tls) {
                p.addLast(Sockets.getSelfSignedTls().newHandler(ch.alloc()));
            }
            p.addLast(new HttpServerCodec(),
                    new HttpServerExpectContinueHandler(),
                    new HttpContentCompressor(),
                    new ChunkedWriteHandler(),
                    new ServerHandler());
        });
        serverBootstrap.bind(port);
    }

    @Override
    protected void dispose() {
        Sockets.closeBootstrap(serverBootstrap);
        synchronized (HttpServer.class) {
            if (DEFAULT == this) {
                DEFAULT = null;
            }
        }
    }

    public HttpServer requestMapping(String path, Handler handler) {
        mapping.put(normalize(path), handler);
        return this;
    }

    public HttpServer requestBlocking(String path, Handler handler) {
        return requestMapping(path, blocking(handler));
    }

    public HttpServer requestDiagnostic() {
        return requestDiagnostic("/rx-diagnostic");
    }

    public HttpServer requestDiagnostic(String path) {
        return requestBlocking(path, new DiagnosticHttpHandler());
    }

    public static HttpServer getDefault() {
        return DEFAULT;
    }

    public static HttpServer ensureDefault(int port) {
        return ensureDefault(port, false);
    }

    public static synchronized HttpServer ensureDefault(int port, boolean tls) {
        if (port <= 0) {
            return DEFAULT;
        }
        HttpServer server = DEFAULT;
        if (server == null) {
            DEFAULT = server = new HttpServer(port, tls);
            log.info("Default http server bind on port {}", port);
            return server;
        }
        if (server.port != port || server.tls != tls) {
            log.warn("Default http server already bind on port {}, ignore new port {}", server.port, port);
        }
        return server;
    }

    public static String renderTemplate(CharSequence template, Map<String, Object> vars) {
        return Strings.resolveVarExpression(template, vars);
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
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return renderTemplate(new String(out.toByteArray(), StandardCharsets.UTF_8), vars);
        } finally {
            in.close();
        }
    }
}
