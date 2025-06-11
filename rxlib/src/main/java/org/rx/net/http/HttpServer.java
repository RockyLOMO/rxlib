package org.rx.net.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.MultiValueMap;
import org.rx.core.Arrays;
import org.rx.core.Constants;
import org.rx.core.Disposable;
import org.rx.core.Strings;
import org.rx.io.Bytes;
import org.rx.net.Sockets;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static org.rx.core.Extends.ifNull;

@Slf4j
public class HttpServer extends Disposable {
    class ServerHandler extends SimpleChannelInboundHandler<HttpObject> {
        HttpRequest request;
        HttpPostRequestDecoder decoder;
        Handler handler;
        ServerRequest req;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
            if (msg instanceof HttpRequest) {
                request = (HttpRequest) msg;

                if (!request.decoderResult().isSuccess()) {
                    sendError(ctx, BAD_REQUEST);
                    return;
                }
                QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
                handler = mapping.get(queryStringDecoder.path());
                if (handler == null) {
                    sendError(ctx, NOT_FOUND);
                    return;
                }
                HttpMethod[] method = handler.method();
                if (method != null && !Arrays.contains(method, request.method())) {
                    sendError(ctx, METHOD_NOT_ALLOWED);
                    return;
                }

                req = new ServerRequest((InetSocketAddress) ctx.channel().remoteAddress(), request.uri(), request.method());
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
                        decoder = new HttpPostRequestDecoder(factory, request);
                    } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
                        log.error("post decode", e);
                        sendError(ctx, BAD_REQUEST);
                        return;
                    }
                }
            }

            if (msg instanceof HttpContent) {
                if (req == null) {
                    return;
                }

                HttpContent content = (HttpContent) msg;
                if (decoder != null) {
                    try {
                        decoder.offer(content);
                    } catch (HttpPostRequestDecoder.ErrorDataDecoderException e) {
                        log.error("post decode", e);
                        sendError(ctx, BAD_REQUEST);
                        return;
                    }

                    try {
                        while (decoder.hasNext()) {
                            InterfaceHttpData httpData = decoder.next();
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
                        req.setContent(buf = Bytes.heapBuffer(Constants.HEAP_BUF_SIZE, true));
                    }
                    buf.writeBytes(content.content());
                }

                if (msg instanceof LastHttpContent) {
//                    LastHttpContent trailer = (LastHttpContent) msg;
//                    if (!trailer.decoderResult().isSuccess()) {
//                        sendError(ctx, BAD_REQUEST);
//                        return;
//                    }

                    ServerResponse res = new ServerResponse();
                    try {
                        handler.handle(req, res);
                        //redirect
                        if (res.getHeaders().contains(HttpHeaderNames.LOCATION)) {
                            FullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), FOUND, Unpooled.EMPTY_BUFFER);
                            sendResponse(ctx, response);
                            return;
                        }
                    } catch (Throwable e) {
                        log.error("handle", e);
                        sendError(ctx, INTERNAL_SERVER_ERROR);
                        return;
                    }

                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), OK, ifNull(res.getContent(), Unpooled.EMPTY_BUFFER));
                    response.headers().setAll(res.getHeaders());
                    sendResponse(ctx, response);
                }
            }
        }

        private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
            FullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), status,
                    Unpooled.copiedBuffer("Failure: " + status, CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            sendResponse(ctx, response);
        }

        private void sendResponse(ChannelHandlerContext ctx, FullHttpResponse response) {
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            HttpUtil.setContentLength(response, response.content().readableBytes());
            if (keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            } else {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            }
            ChannelFuture future = ctx.writeAndFlush(response);
            if (!keepAlive) {
                future.addListener(ChannelFutureListener.CLOSE);
            }

            if (decoder != null) {
                decoder.destroy();
                decoder = null;
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (decoder != null) {
                decoder.cleanFiles();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.error("caught", cause);
            if (ctx.channel().isActive()) {
                sendError(ctx, INTERNAL_SERVER_ERROR);
            }
        }
    }

    public interface Handler {
        default HttpMethod[] method() {
            return null;
        }

        void handle(ServerRequest request, ServerResponse response) throws Throwable;
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
    final Map<String, Handler> mapping = new ConcurrentHashMap<>();

    @SneakyThrows
    public HttpServer(int port, boolean ssl) {
        final SslContext sslCtx;
        if (ssl) {
            SelfSignedCertificate ssc = new SelfSignedCertificate("*.f-li.cn");
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }
        serverBootstrap = Sockets.serverBootstrap(ch -> {
            ChannelPipeline p = ch.pipeline();
            if (sslCtx != null) {
                p.addLast(sslCtx.newHandler(ch.alloc()));
            }
            p.addLast(new HttpServerCodec(),
                    new HttpServerExpectContinueHandler(),
                    new HttpContentCompressor(),
                    new ChunkedWriteHandler(),
                    new ServerHandler());
        });
        serverBootstrap.bind(port).addListener(Sockets.logBind(port));
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(serverBootstrap);
    }

    public HttpServer requestMapping(String path, Handler handler) {
        mapping.put(normalize(path), handler);
        return this;
    }
}
