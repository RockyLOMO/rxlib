package org.rx.net.rpc;

import io.netty.channel.ChannelId;
import lombok.*;
import org.rx.bean.DateTime;
import org.rx.net.rpc.protocol.HandshakePacket;

import java.io.Serializable;
import java.net.InetSocketAddress;

@EqualsAndHashCode
@RequiredArgsConstructor
public class RpcServerClient implements Serializable {
    private static final long serialVersionUID = 2476124476157968806L;
    @Getter
    private final DateTime connectedTime = DateTime.now();
    @Getter
    private final ChannelId id;
    @Getter
    private final InetSocketAddress remoteAddress;
    @Getter
    @Setter
    private HandshakePacket handshakePacket;

    public volatile Object userState;
}
