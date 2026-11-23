package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.AttributeKey;
import org.rx.net.transport.AdaptiveStream;
import org.rx.net.transport.AdaptiveUdpPort;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/** Private RSS hop method. Standard SOCKS peers select their usual authentication method. */
final class SocksAdaptiveHandshake {
    static final Socks5AuthMethod METHOD = new Socks5AuthMethod(0x83);
    static final DefaultSocks5InitialRequest OFFER = new DefaultSocks5InitialRequest(
            Arrays.asList(Socks5AuthMethod.NO_AUTH, Socks5AuthMethod.PASSWORD, METHOD));
    static final DefaultSocks5InitialResponse SELECTED = new DefaultSocks5InitialResponse(METHOD);
    static final AttributeKey<Boolean> NEGOTIATED = AttributeKey.valueOf("rssAdaptiveNegotiated");
    static final AttributeKey<byte[]> NONCE = AttributeKey.valueOf("rssAdaptiveNonce");
    private static final SecureRandom RANDOM = new SecureRandom();

    static byte[] sendNonce(ChannelHandlerContext ctx, AdaptiveUdpPort port) {
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        ByteBuffer.wrap(nonce, 28, 4).putShort((short) port.port()).putShort((short) port.aliasPort());
        ctx.writeAndFlush(ctx.alloc().buffer(32, 32).writeBytes(nonce));
        return nonce;
    }

    static void awaitNonce(ChannelPipeline p, String passwordDecoder) {
        p.addBefore(passwordDecoder, "rssNonce", new ByteToMessageDecoder() {
            @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                if (in.readableBytes() < 32) return;
                byte[] nonce = new byte[32];
                in.readBytes(nonce);
                ctx.channel().attr(NONCE).set(nonce);
                ctx.pipeline().remove(this);
            }
        });
    }

    static void install(Channel tcp, byte[] nonce, String password, AdaptiveUdpPort serverPort) {
        byte[] key;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            key = mac.doFinal(nonce);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        AdaptiveTcpHandler handler = new AdaptiveTcpHandler(ByteBuffer.wrap(nonce).getLong(), key, serverPort,
                ByteBuffer.wrap(nonce).getShort(28) & 0xffff, ByteBuffer.wrap(nonce).getShort(30) & 0xffff);
        tcp.pipeline().addFirst("rssAdaptive", handler);
        tcp.pipeline().addFirst("rssAdaptiveLength", new LengthFieldBasedFrameDecoder(
                AdaptiveStream.MAX_TCP_FRAME + 2, 0, 2, 0, 2));
    }
}
