package org.rx.net.socks;

import org.rx.net.rss.tcp.AdaptiveTcpHandler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.socksx.v5.*;
import io.netty.util.AttributeKey;
import org.rx.diagnostic.DiagnosticMetrics;
import org.rx.net.rss.tcp.AdaptiveUdpPort;

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
    // The method identifies the wire profile. Peers without it may select ordinary SOCKS/TCP.
    private static final Socks5AuthMethod[] METHODS = {
            new Socks5AuthMethod(0x83), new Socks5AuthMethod(0x84), new Socks5AuthMethod(0x85)
    };
    private static final Socks5AuthMethod NO_HOP_METHOD = new Socks5AuthMethod(0x87);
    private static final String[] PROFILE_TAGS = {"profile=daily", "profile=game", "profile=weak"};
    private static final DefaultSocks5InitialRequest[] OFFERS = new DefaultSocks5InitialRequest[METHODS.length];
    private static final DefaultSocks5InitialRequest[] NO_HOP_OFFERS = new DefaultSocks5InitialRequest[METHODS.length];
    private static final DefaultSocks5InitialResponse[] RESPONSES = new DefaultSocks5InitialResponse[METHODS.length];
    static final AttributeKey<Integer> NEGOTIATED = AttributeKey.valueOf("rssAdaptiveNegotiated");
    static final AttributeKey<Boolean> NO_HOP = AttributeKey.valueOf("rssAdaptiveNoHop");
    static final AttributeKey<byte[]> NONCE = AttributeKey.valueOf("rssAdaptiveNonce");
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        for (int i = 0; i < METHODS.length; i++) {
            OFFERS[i] = new DefaultSocks5InitialRequest(Arrays.asList(Socks5AuthMethod.NO_AUTH, Socks5AuthMethod.PASSWORD, METHODS[i]));
            NO_HOP_OFFERS[i] = new DefaultSocks5InitialRequest(Arrays.asList(
                    Socks5AuthMethod.NO_AUTH, Socks5AuthMethod.PASSWORD, METHODS[i], NO_HOP_METHOD));
            RESPONSES[i] = new DefaultSocks5InitialResponse(METHODS[i]);
        }
    }

    static DefaultSocks5InitialRequest offer(int profile) {
        return offer(profile, true);
    }

    static DefaultSocks5InitialRequest offer(int profile, boolean udpHop) {
        if (profile < 0 || profile >= OFFERS.length) {
            throw new IllegalArgumentException("Unknown RSS adaptive profile: " + profile);
        }
        return udpHop ? OFFERS[profile] : NO_HOP_OFFERS[profile];
    }

    static boolean selected(int profile, Socks5AuthMethod method) {
        if (method.equals(Socks5AuthMethod.PASSWORD) || method.equals(Socks5AuthMethod.NO_AUTH)) {
            DiagnosticMetrics.record("rss.tcp.adaptive.negotiationFallback", 1, PROFILE_TAGS[profile]);
            return false;
        }
        return METHODS[profile].equals(method);
    }

    static boolean select(ChannelHandlerContext ctx, List<Socks5AuthMethod> offered) {
        for (int i = 0; i < METHODS.length; i++) {
            if (offered.contains(METHODS[i])) {
                ctx.channel().attr(NEGOTIATED).set(i);
                ctx.channel().attr(NO_HOP).set(offered.contains(NO_HOP_METHOD));
                ctx.writeAndFlush(RESPONSES[i]);
                return true;
            }
        }
        return false;
    }

    static byte[] sendNonce(ChannelHandlerContext ctx, AdaptiveUdpPort port) {
        return sendNonce(ctx, port, true);
    }

    static byte[] sendNonce(ChannelHandlerContext ctx, AdaptiveUdpPort port, boolean udpHop) {
        byte[] nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        int primary = port.port();
        int alias = udpHop ? port.aliasPort() : primary;
        ByteBuffer.wrap(nonce, 28, 4).putShort((short) primary).putShort((short) alias);
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

    static void install(Channel tcp, byte[] nonce, String password, AdaptiveUdpPort serverPort, int profile) {
        install(tcp, nonce, password, serverPort, profile, true);
    }

    static void install(Channel tcp, byte[] nonce, String password, AdaptiveUdpPort serverPort, int profile, boolean udpHop) {
        byte[] key;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(password.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (profile != 0) {
                mac.update((byte) profile);
            }
            key = mac.doFinal(nonce);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        int primary = ByteBuffer.wrap(nonce).getShort(28) & 0xffff;
        int alias = udpHop ? (ByteBuffer.wrap(nonce).getShort(30) & 0xffff) : primary;
        AdaptiveTcpHandler.install(tcp, ByteBuffer.wrap(nonce).getLong(), key, serverPort,
                primary, alias, profile);
    }
}
