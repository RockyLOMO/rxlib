package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DefaultDnsQuery;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.http.*;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;
import lombok.extern.slf4j.Slf4j;
import org.rx.net.Sockets;

import java.net.InetAddress;
import java.net.InetSocketAddress;

@Slf4j
public class DoHServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    static final AsciiString CONTENT_TYPE_DNS = AsciiString.cached("application/dns-message");
    final DnsServer server;

    public DoHServerHandler(DnsServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        DnsDoHConfig config = server.getDohConfig();
        if (config == null || !config.isEnabled()) {
            writeStatus(ctx, HttpResponseStatus.NOT_FOUND);
            return;
        }
        if (!config.getPath().equals(request.uri())) {
            writeStatus(ctx, HttpResponseStatus.NOT_FOUND);
            return;
        }
        if (request.method() != HttpMethod.POST) {
            writeStatus(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType == null || !contentType.toLowerCase().startsWith(CONTENT_TYPE_DNS.toString())) {
            writeStatus(ctx, HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        if (request.content().readableBytes() > config.getMaxDnsMessageBytes()) {
            writeStatus(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        DefaultDnsQuery query;
        try {
            query = DoHMessageCodec.decodeQuery(request.content().duplicate());
        } catch (Exception e) {
            log.warn("Invalid DoH query from {}", ctx.channel().remoteAddress(), e);
            writeStatus(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        InetAddress srcIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress();
        DnsClient upstream = Sockets.getAttr(ctx.channel(), DnsServer.ATTR_UPSTREAM);
        ctx.channel().config().setAutoRead(false);
        Promise<DefaultDnsResponse> promise = DnsResolveCore.resolve(server, upstream, srcIp, query, true, ctx.executor());
        promise.addListener(f -> ctx.executor().execute(() -> {
            DefaultDnsResponse dnsResponse = null;
            try {
                if (f.isSuccess()) {
                    dnsResponse = promise.getNow();
                } else {
                    log.error("DoH resolve fail {}", ctx.channel().remoteAddress(), f.cause());
                    dnsResponse = DnsMessageUtil.newErrorResponse(query, DnsResponseCode.SERVFAIL);
                }
                ByteBuf body = ctx.alloc().buffer(Math.max(64, dnsResponse.count(io.netty.handler.codec.dns.DnsSection.ANSWER) * 32));
                DoHMessageCodec.encodeResponse(body, dnsResponse);
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, body);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_DNS);
                response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
                if (!HttpUtil.isKeepAlive(request)) {
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                } else {
                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    ctx.writeAndFlush(response);
                }
            } finally {
                ReferenceCountUtil.release(dnsResponse);
                query.release();
                if (ctx.channel().isActive()) {
                    ctx.channel().config().setAutoRead(true);
                }
            }
        }));
    }

    private void writeStatus(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response);
    }
}
