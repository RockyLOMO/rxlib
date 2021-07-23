package org.rx.net.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Arrays;
import org.rx.core.Disposable;
import org.rx.net.Sockets;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.http.HttpResponseStatus.*;

@Slf4j
public class HttpServer extends Disposable {
    class ServerHandler extends SimpleChannelInboundHandler<HttpObject> {
        HttpRequest request;
        Handler handler;
        RequestBean req;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
            if (msg instanceof HttpRequest) {
                request = (HttpRequest) msg;

                if (!request.decoderResult().isSuccess()) {
                    sendError(ctx, BAD_REQUEST);
                    return;
                }
                URI uri = new URI(request.uri());
                handler = mapping.get(uri.getPath());
                if (handler == null) {
                    sendError(ctx, NOT_FOUND);
                    return;
                }
                HttpMethod[] method = handler.method();
                if (method != null && !Arrays.contains(method, request.method())) {
                    sendError(ctx, METHOD_NOT_ALLOWED);
                    return;
                }

                req = new RequestBean(request.uri(), request.method());
                req.getHeaders().setAll(request.headers());

                QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());
                Map<String, List<String>> params = queryStringDecoder.parameters();
                if (!params.isEmpty()) {
                    for (Map.Entry<String, List<String>> p : params.entrySet()) {
                        String key = p.getKey();
                        for (String val : p.getValue()) {
                            req.getQueryString().put(key, val);
                        }
                    }
                }
            }

            if (msg instanceof HttpContent) {
                HttpContent httpContent = (HttpContent) msg;
                ByteBuf content = httpContent.content();
                if (content.isReadable()) {
                    //todo isjson or form
                    req.setContent(content);
                }

                if (msg instanceof LastHttpContent) {
                    LastHttpContent trailer = (LastHttpContent) msg;
                    if (!trailer.decoderResult().isSuccess()) {
                        sendError(ctx, BAD_REQUEST);
                        return;
                    }

                    ResponseBean res = new ResponseBean();
                    try {
                        handler.handle(req, res);
                    } catch (Throwable e) {
                        sendError(ctx, INTERNAL_SERVER_ERROR);
                        return;
                    }

                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), OK, res.getContent());
                    response.headers().setAll(res.getHeaders());
                    Set<Cookie> cookies = res.getCookies();
                    if (!cookies.isEmpty()) {
                        for (Cookie cookie : cookies) {
                            response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
                        }
                    }
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, res.getContentType());
                    //todo json or form
                    sendResponse(ctx, response);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.error("caught", cause);
            if (ctx.channel().isActive()) {
                sendError(ctx, INTERNAL_SERVER_ERROR);
            }
        }

        private void sendRedirect(ChannelHandlerContext ctx, String newUri) {
            FullHttpResponse response = new DefaultFullHttpResponse(request.protocolVersion(), FOUND, Unpooled.EMPTY_BUFFER);
            response.headers().set(HttpHeaderNames.LOCATION, newUri);
            sendResponse(ctx, response);
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
            ChannelFuture flushPromise = ctx.writeAndFlush(response);
            if (!keepAlive) {
                // Close the connection as soon as the response is sent.
                flushPromise.addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    interface Handler {
        default HttpMethod[] method() {
            return null;
        }

        default boolean isRequestBody() {
            return false;
        }

        default boolean isResponseBody() {
            return false;
        }

        void handle(RequestBean request, ResponseBean response);
    }

    final ServerBootstrap bootstrap;
    final Map<String, Handler> mapping = new ConcurrentHashMap<>();

    public HttpServer() {
        bootstrap = Sockets.serverBootstrap(channel -> {
            channel.pipeline().addLast(new HttpServerCodec(),
                    new HttpServerExpectContinueHandler(),
                    new HttpContentCompressor(),
                    new ChunkedWriteHandler(),
                    new ServerHandler());
        });
    }

    @Override
    protected void freeObjects() {
        Sockets.closeBootstrap(bootstrap);
    }
}
