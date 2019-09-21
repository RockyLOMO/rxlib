package org.rx.socks.tcp;

import io.netty.channel.ChannelHandler;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.core.Contract;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.function.Supplier;

@Data
@RequiredArgsConstructor
public class TcpConfig implements Serializable {
    public static TcpConfig packetConfig(InetSocketAddress endpoint) {
        TcpConfig config = new TcpConfig(endpoint);
        config.setEnableSsl(true);
        config.setEnableCompress(true);
        return config;
    }

    private final InetSocketAddress endpoint;
    private int connectTimeout = Contract.config.getDefaultSocksTimeout();
    private boolean autoRead = true;
    private boolean enableSsl;
    private boolean enableCompress;
    //not shareable
    private Supplier<ChannelHandler[]> handlersSupplier;
}
