package org.rx.net.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.compression.ZlibWrapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import lombok.SneakyThrows;
import org.rx.bean.FlagsEnum;
import org.rx.net.AESHandler;

import java.net.InetSocketAddress;

public class SslUtil {
    @SneakyThrows
    public static void addFrontendHandler(Channel channel, FlagsEnum<TransportFlags> flags) {
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
//            pipeline.addLast(new AESHandler(AESUtil.dailyKey().getBytes(StandardCharsets.UTF_8)));
            pipeline.addLast(new AESHandler());
        }
        if (flags.has(TransportFlags.FRONTEND_COMPRESS)) {
            pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP), ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
        }
    }

    @SneakyThrows
    public static void addBackendHandler(Channel channel, FlagsEnum<TransportFlags> flags, InetSocketAddress remoteEndpoint, boolean reverse) {
        if (flags == null) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (reverse) {
            if (flags.has(TransportFlags.BACKEND_COMPRESS)) {
                pipeline.addFirst(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP), ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
            }
            if (flags.has(TransportFlags.BACKEND_AES)) {
//                pipeline.addFirst(new AESHandler(AESUtil.dailyKey().getBytes(StandardCharsets.UTF_8)));
                pipeline.addFirst(new AESHandler());
            }
            if (flags.has(TransportFlags.BACKEND_SSL)) {
                SslContext sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                pipeline.addFirst(sslCtx.newHandler(channel.alloc(), remoteEndpoint.getHostString(), remoteEndpoint.getPort()));
            }
            return;
        }

        if (flags.has(TransportFlags.BACKEND_SSL)) {
            SslContext sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            pipeline.addLast(sslCtx.newHandler(channel.alloc(), remoteEndpoint.getHostString(), remoteEndpoint.getPort()));
        }
        if (flags.has(TransportFlags.BACKEND_AES)) {
//            pipeline.addLast(new AESHandler(AESUtil.dailyKey().getBytes(StandardCharsets.UTF_8)));
            pipeline.addLast(new AESHandler());
        }
        if (flags.has(TransportFlags.BACKEND_COMPRESS)) {
            pipeline.addLast(ZlibCodecFactory.newZlibEncoder(ZlibWrapper.GZIP), ZlibCodecFactory.newZlibDecoder(ZlibWrapper.GZIP));
        }
    }
}
