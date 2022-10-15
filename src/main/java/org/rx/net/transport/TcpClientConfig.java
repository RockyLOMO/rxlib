package org.rx.net.transport;

import io.netty.handler.codec.serialization.ClassResolver;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectEncoder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.rx.net.SocketConfig;
import org.rx.net.rpc.RpcClientConfig;

import javax.validation.constraints.NotNull;
import java.net.InetSocketAddress;

@Data
@EqualsAndHashCode(callSuper = true)
public class TcpClientConfig extends SocketConfig {
    private static final long serialVersionUID = -1177044491381236637L;
    public static final ObjectEncoder DEFAULT_ENCODER = new ObjectEncoder();
    public static final ClassResolver DEFAULT_CLASS_RESOLVER = ClassResolvers.softCachingConcurrentResolver(RpcClientConfig.class.getClassLoader());

    @NotNull
    private volatile InetSocketAddress serverEndpoint;
    private volatile boolean enableReconnect;
    private int heartbeatTimeout = 60;
}
