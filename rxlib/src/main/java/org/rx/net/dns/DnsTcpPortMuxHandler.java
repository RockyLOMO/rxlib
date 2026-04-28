package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.dns.TcpDnsQueryDecoder;
import io.netty.handler.codec.dns.TcpDnsResponseEncoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;

import java.util.List;

public class DnsTcpPortMuxHandler extends ByteToMessageDecoder {
    final DnsServer server;

    public DnsTcpPortMuxHandler(DnsServer server) {
        this.server = server;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 2) {
            return;
        }
        DnsDoHConfig config = server.getDohConfig();
        boolean dohEnabled = config != null && config.isEnabled();
        int b0 = in.getUnsignedByte(in.readerIndex());

        if (dohEnabled && b0 == 0x16) {
            if (in.readableBytes() < 3) {
                return;
            }
            int b1 = in.getUnsignedByte(in.readerIndex() + 1);
            if (b1 == 0x03) {
                installDoH(ctx, config, true);
                forward(ctx, in);
                return;
            }
        }

        if (dohEnabled && config.isAllowPlainHttp() && (b0 == 'P' || b0 == 'G')) {
            if (in.readableBytes() < 5) {
                return;
            }
            if (startsWith(in, "POST ") || startsWith(in, "GET ")) {
                installDoH(ctx, config, false);
                forward(ctx, in);
                return;
            }
        }

        installDns(ctx);
        forward(ctx, in);
    }

    private void installDns(ChannelHandlerContext ctx) {
        ChannelPipeline p = ctx.pipeline();
        p.addLast(new TcpDnsQueryDecoder());
        p.addLast(new TcpDnsResponseEncoder());
        p.addLast(DnsHandler.DEFAULT);
        p.remove(this);
    }

    private void installDoH(ChannelHandlerContext ctx, DnsDoHConfig config, boolean tls) {
        ChannelPipeline p = ctx.pipeline();
        if (tls) {
            SslContext sslContext = config.getSslContext();
            if (sslContext == null) {
                installDns(ctx);
                return;
            }
            p.addLast(sslContext.newHandler(ctx.alloc()));
        }
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(config.getMaxDnsMessageBytes()));
        p.addLast(new DoHServerHandler(server));
        p.remove(this);
    }

    private void forward(ChannelHandlerContext ctx, ByteBuf in) {
        ByteBuf msg = in.readRetainedSlice(in.readableBytes());
        ctx.fireChannelRead(msg);
    }

    private boolean startsWith(ByteBuf in, String prefix) {
        if (in.readableBytes() < prefix.length()) {
            return false;
        }
        int index = in.readerIndex();
        for (int i = 0; i < prefix.length(); i++) {
            if (in.getUnsignedByte(index + i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
