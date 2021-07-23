package org.rx.net.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.RxConfig;
import org.rx.net.Sockets;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderValues.*;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_0;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Slf4j
public class HttpServer {
    class xxx extends SimpleChannelInboundHandler<HttpObject> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {        if (!request.decoderResult().isSuccess()) {
            sendError(ctx, BAD_REQUEST);
            return;
        }
FullHttpMessage e;e.content()
            if (msg instanceof HttpRequest) {
                HttpRequest req = (HttpRequest) msg;


                boolean keepAlive = HttpUtil.isKeepAlive(req);
                FullHttpResponse response = new DefaultFullHttpResponse(req.protocolVersion(), OK,
                        Unpooled.wrappedBuffer(CONTENT));
                response.headers()
                        .set(CONTENT_TYPE, TEXT_PLAIN)
                        .setInt(CONTENT_LENGTH, response.content().readableBytes());

                if (keepAlive) {
                    if (!req.protocolVersion().isKeepAliveDefault()) {
                        response.headers().set(CONNECTION, KEEP_ALIVE);
                    }
                } else {
                    // Tell the client we're going to close the connection.
                    response.headers().set(CONNECTION, CLOSE);
                }

                ChannelFuture f = ctx.write(response);
                if (!keepAlive) {
                    f.addListener(ChannelFutureListener.CLOSE);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            log.error("caught", cause);
            Sockets.closeOnFlushed(ctx.channel());       if (ctx.channel().isActive()) {
                sendError(ctx, INTERNAL_SERVER_ERROR);
            }
        }

        private void sendRedirect(ChannelHandlerContext ctx, String newUri) {
            FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, FOUND, Unpooled.EMPTY_BUFFER);
            response.headers().set(HttpHeaderNames.LOCATION, newUri);

            sendAndCleanupConnection(ctx, response);
        }

        private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, status, Unpooled.copiedBuffer("Failure: " + status + "\r\n", CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");

            sendAndCleanupConnection(ctx, response);
        }

        /**
         * If Keep-Alive is disabled, attaches "Connection: close" header to the response
         * and closes the connection after the response being sent.
         */
        private void sendAndCleanupConnection(ChannelHandlerContext ctx, FullHttpResponse response) {
            final FullHttpRequest request = this.request;
            final boolean keepAlive = HttpUtil.isKeepAlive(request);
            HttpUtil.setContentLength(response, response.content().readableBytes());
            if (!keepAlive) {
                // We're going to close the connection as soon as the response is sent,
                // so we should also make it clear for the client.
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            } else if (request.protocolVersion().equals(HTTP_1_0)) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            ChannelFuture flushPromise = ctx.writeAndFlush(response);

            if (!keepAlive) {
                // Close the connection as soon as the response is sent.
                flushPromise.addListener(ChannelFutureListener.CLOSE);
            }
        }
        ServerCookieDecoder
    }

    public HttpServer() {
        Sockets.serverBootstrap(channel -> {
            channel.pipeline().addLast(new HttpServerCodec(),
                    new HttpServerExpectContinueHandler(),
                    new HttpContentCompressor(),
                    new ChunkedWriteHandler());
        });
    }
}
