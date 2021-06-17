package org.rx.net.shadowsocks.obfs.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class HttpSimpleHandler extends SimpleChannelInboundHandler<HttpObject> {
    public static ByteBuf HTTP_SIMPLE_DELIMITER = Unpooled.copiedBuffer("\r\n".getBytes());
    public static final String OBFS_NAME = "http_simple";

    public static List<ChannelHandler> getHandlers() {
        List<ChannelHandler> channels = new ArrayList<>();
        channels.add(new HttpServerCodec());
        channels.add(new HttpSimpleHandler());
        return channels;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        if (msg.decoderResult() != DecoderResult.SUCCESS) {
            log.error("simple_http decode error, pip close");
            ctx.close();
            return;
        }

        if (msg instanceof HttpRequest) {
            log.debug(((HttpRequest) msg).uri());
            String[] hexItems = ((HttpRequest) msg).uri().split("%");
            StringBuilder hexStr = new StringBuilder();
            if (hexItems.length > 1) {
                for (int i = 1; i < hexItems.length; i++) {
                    if (hexItems[i].length() == 2) {
                        hexStr.append(hexItems[i]);
                    } else {
                        hexStr.append(hexItems[i], 0, 2);
                        break;
                    }
                }
            }
            ByteBuf encodeData = Unpooled.wrappedBuffer(Hex.decode(hexStr.toString()));
            ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
            ctx.fireChannelRead(encodeData);
        } else if (msg instanceof HttpContent) {
            ctx.pipeline().remove(HttpServerCodec.class);
            ctx.pipeline().remove(this);
        }
    }
}
