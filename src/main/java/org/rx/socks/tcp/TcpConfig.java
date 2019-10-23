package org.rx.socks.tcp;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.socks.MemoryMode;
import org.rx.socks.Sockets;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

import static org.rx.core.App.Config;

@Data
@RequiredArgsConstructor
public class TcpConfig {
    public static TcpClient packetClient(InetSocketAddress serverEndpoint, String appId) {
        TcpClient client = new TcpClient(packetConfig(serverEndpoint), appId);
        client.getConfig().setHandlersSupplier(() -> new ChannelHandler[]{
                new ObjectEncoder(),
                new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(TcpConfig.class.getClassLoader())),
                new PacketClientHandler(client)
        });
        return client;
    }

    public static <T extends SessionClient> TcpServer<T> packetServer(int port, Class sessionClientType) {
        TcpServer<T> server = new TcpServer<>(packetConfig(Sockets.getAnyEndpoint(port)), sessionClientType);
        server.getConfig().setHandlersSupplier(() -> new ChannelHandler[]{
                new ObjectEncoder(),
                new ObjectDecoder(ClassResolvers.weakCachingConcurrentResolver(TcpConfig.class.getClassLoader())),
                new PacketServerHandler<>(server)
        });
        return server;
    }

    private static TcpConfig packetConfig(InetSocketAddress endpoint) {
        TcpConfig config = new TcpConfig(endpoint);
        config.setEnableSsl(true);
        config.setEnableCompress(true);
        return config;
    }

    private final InetSocketAddress endpoint;
    private int workThread;
    private MemoryMode memoryMode;
    private int connectTimeout = Config.getSocksTimeout();
    private boolean enableSsl;
    private boolean enableCompress;
    //not shareable
    private Supplier<ChannelHandler[]> handlersSupplier;
}
