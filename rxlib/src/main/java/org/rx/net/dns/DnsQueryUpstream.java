package org.rx.net.dns;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.dns.*;
import org.rx.core.Tasks;
import org.rx.net.socks.upstream.Upstream;

import java.net.InetSocketAddress;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** An authorized in-process DNS endpoint. UDP replies retain the requesting SOCKS destination. */
public abstract class DnsQueryUpstream extends Upstream {
    private static final Semaphore IN_FLIGHT = new Semaphore(128);

    protected DnsQueryUpstream(InetSocketAddress destination) { super(destination); }

    protected abstract byte[] answer(DefaultDnsQuery query, boolean tcp) throws Exception;

    public void installTcp(Channel channel) {
        channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(65537, 0, 2, 0, 2),
                new LengthFieldPrepender(2), new SimpleChannelInboundHandler<ByteBuf>() {
            @Override protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                query(channel, message, true, channel::writeAndFlush);
            }
        });
    }

    /** The input stays owned by the caller. The reply consumer owns its output. */
    public void query(Channel channel, ByteBuf input, boolean tcp, Consumer<ByteBuf> reply) {
        int start = input.readerIndex();
        if (input.readableBytes() < 12 || (input.getUnsignedShort(start + 2) & 0xf800) != 0) {
            if (tcp) channel.close();
            return;
        }
        DefaultDnsQuery query;
        try {
            query = DoHMessageCodec.decodeQuery(input.slice(start, input.readableBytes()));
        } catch (RuntimeException malformed) {
            if (tcp) channel.close();
            return;
        }
        if (!IN_FLIGHT.tryAcquire()) {
            try {
                reply.accept(io.netty.buffer.Unpooled.wrappedBuffer(error(query, DnsResponseCode.SERVFAIL)));
            } finally {
                query.release();
            }
            return;
        }
        try {
            Tasks.run(() -> {
                try {
                    byte[] response;
                    try {
                        response = answer(query, tcp);
                    } catch (Exception failure) {
                        response = error(query, DnsResponseCode.SERVFAIL);
                    }
                    if (!tcp && response.length > 512) {
                        response = error(query, DnsResponseCode.NOERROR);
                        response[2] |= 2; // TC, retry over TCP
                    }
                    final byte[] bytes = response;
                    channel.eventLoop().execute(() -> {
                        if (channel.isActive()) reply.accept(io.netty.buffer.Unpooled.wrappedBuffer(bytes));
                    });
                } finally {
                    query.release();
                    IN_FLIGHT.release();
                }
            });
        } catch (RuntimeException rejected) {
            query.release();
            IN_FLIGHT.release();
            throw rejected;
        }
    }

    protected static byte[] error(DefaultDnsQuery query, DnsResponseCode code) {
        return encode(DnsMessageUtil.newErrorResponse(query, code));
    }

    protected static byte[] encode(DefaultDnsResponse response) {
        ByteBuf buffer = io.netty.buffer.Unpooled.buffer(256, 65535);
        try {
            DoHMessageCodec.encodeResponse(buffer, response);
            return io.netty.buffer.ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
            response.release();
        }
    }
}
