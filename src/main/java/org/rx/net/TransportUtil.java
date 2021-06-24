package org.rx.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.rx.bean.FlagsEnum;
import org.rx.core.exception.InvalidException;

import java.net.InetSocketAddress;

public class TransportUtil {
    public static final String ZIP_ENCODER = "ZIP_ENCODER";
    public static final String ZIP_DECODER = "ZIP_DECODER";

    @SneakyThrows
    public static void addFrontendHandler(Channel channel, @NonNull SocketConfig config) {
        FlagsEnum<TransportFlags> flags = config.getTransportFlags();
        if (flags == null) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (flags.has(TransportFlags.FRONTEND_SSL)) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
            pipeline.addLast(sslCtx.newHandler(channel.alloc()));
        }
        if (flags.has(TransportFlags.FRONTEND_AES)) {
            if (config.getAesKey() == null) {
                throw new InvalidException("AES key is empty");
            }
            pipeline.addLast(new AESCodec(config.getAesKey()).channelHandlers());
        }
        if (flags.has(TransportFlags.FRONTEND_COMPRESS)) {
            pipeline.addLast(ZIP_ENCODER, ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP))
                    .addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
        }
    }

    @SneakyThrows
    public static void addBackendHandler(Channel channel, @NonNull SocketConfig config, InetSocketAddress remoteEndpoint) {
        FlagsEnum<TransportFlags> flags = config.getTransportFlags();
        if (flags == null) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (flags.has(TransportFlags.BACKEND_SSL)) {
            SslContext sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            pipeline.addLast(sslCtx.newHandler(channel.alloc(), remoteEndpoint.getHostString(), remoteEndpoint.getPort()));
        }
        if (flags.has(TransportFlags.BACKEND_AES)) {
            if (config.getAesKey() == null) {
                throw new InvalidException("AES key is empty");
            }
            pipeline.addLast(new AESCodec(config.getAesKey()).channelHandlers());
        }
        if (flags.has(TransportFlags.BACKEND_COMPRESS)) {
            pipeline.addLast(ZIP_ENCODER, ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP))
                    .addLast(ZIP_DECODER, ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
        }
    }
}
