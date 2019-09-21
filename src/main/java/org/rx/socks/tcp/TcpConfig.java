package org.rx.socks.tcp;

import io.netty.channel.ChannelHandler;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.core.Arrays;
import org.rx.core.Contract;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.List;

@Data
@RequiredArgsConstructor
public class TcpConfig implements Serializable {
    public static TcpConfig packetConfig(InetSocketAddress endpoint, ChannelHandler... channelHandlers) {
        TcpConfig config = new TcpConfig(endpoint, Arrays.toList(channelHandlers));
        config.setEnableSsl(true);
        config.setEnableCompress(true);
        return config;
    }

    private final InetSocketAddress endpoint;
    private int connectTimeout = Contract.config.getDefaultSocksTimeout();
    private boolean autoRead = true;
    private boolean enableSsl;
    private boolean enableCompress;
    private final List<ChannelHandler> handlers;
}
