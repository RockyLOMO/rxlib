package org.rx.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import lombok.SneakyThrows;
import org.rx.net.socks.SocksConfig;
import javax.crypto.spec.SecretKeySpec;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;

/** RSS TCP hop. One listener accepts raw SOCKS or zlib, optionally protected by TLS or PSK AES. */
public final class RssHopTls {
    private RssHopTls() { }

    @SneakyThrows
    private static SslContext build(SslContextBuilder builder) {
        return builder.sslProvider(SslProvider.JDK).protocols("TLSv1.3")
                .ciphers(Collections.singletonList("TLS_AES_128_GCM_SHA256")).build();
    }

    public static SslContext serverFromEnvironment() {
        String cert = System.getenv("RSS_TCP_TLS_CERT"), key = System.getenv("RSS_TCP_TLS_KEY");
        if (cert == null && key == null) {
            return null;
        }
        if (cert == null || key == null) {
            throw new IllegalArgumentException("Set both RSS_TCP_TLS_CERT and RSS_TCP_TLS_KEY to PEM file paths");
        }
        return serverContext(new File(cert), new File(key));
    }

    public static SslContext serverContext(File chain, File privateKey) {
        return build(SslContextBuilder.forServer(chain, privateKey));
    }

    public static SslContext clientContext(File ca) {
        SslContextBuilder builder = SslContextBuilder.forClient().endpointIdentificationAlgorithm("HTTPS");
        return build(ca == null ? builder : builder.trustManager(ca));
    }

    private static final class Client {
        static final String CA = System.getenv("RSS_TCP_TLS_CA");
        static final SslContext CONTEXT = clientContext(CA == null ? null : new File(CA));
    }

    public static SslContext clientFromEnvironment() {
        return Client.CONTEXT;
    }

    static SslHandler clientHandler(Channel ch, SslContext context, InetSocketAddress peer) {
        if (peer == null || context == null) {
            throw new IllegalArgumentException("RSS TLS requires a client context and an upstream hostname or IP");
        }
        return context.newHandler(ch.alloc(), peer.getHostString(), peer.getPort());
    }

    static ChannelHandler serverSelector(SocksConfig config) {
        return serverSelector(config, RssHopAes.configuredKey());
    }

    static ChannelHandler serverSelector(SocksConfig config, SecretKeySpec key) {
        return new ServerSelector(config, config.getRssTlsContext(), key);
    }

    /** The first SOCKS greeting can be just three bytes. Consume no bytes while selecting its decoder. */
    private static final class ServerSelector extends ByteToMessageDecoder {
        private final SocksConfig config;
        private final SslContext tls;
        private final SecretKeySpec key;

        ServerSelector(SocksConfig config, SslContext tls, SecretKeySpec key) {
            this.config = config;
            this.tls = tls;
            this.key = key;
        }

        @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            int pos = in.readerIndex();
            int first = in.getUnsignedByte(pos);
            if (first == 5) {
                ctx.pipeline().remove(this);
                return;
            }
            if (first == RssHopAes.MAGIC && key != null) {
                ctx.pipeline().addAfter(ctx.name(), null, new ServerSelector(config, null, null));
                ctx.pipeline().replace(this, null, new RssHopAes(false, key));
                return;
            }
            if (first == 0x16 && tls != null) { // TLS ClientHello
                ctx.pipeline().addAfter(ctx.name(), null, new ServerSelector(config, null, null));
                ctx.pipeline().replace(this, null, tls.newHandler(ctx.alloc()));
                return;
            }
            if ((first & 0x0f) == 8 && first <= 0x78) { // RFC 1950, DEFLATE with at most a 32 KiB window
                if (in.readableBytes() < 2) {
                    return;
                }
                int header = in.getUnsignedShort(pos);
                if (header % 31 == 0 && (header & 0x20) == 0) {
                    ctx.pipeline().addAfter(ctx.name(), Sockets.ZIP_ENCODER,
                            Sockets.newTcpCompressionEncoder(config, ZlibWrapper.ZLIB));
                    ctx.pipeline().replace(this, Sockets.ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(ZlibWrapper.ZLIB));
                    return;
                }
            }
            in.skipBytes(in.readableBytes());
            ctx.close();
        }
    }
}
