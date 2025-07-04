package org.rx.net.socks;

import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.rx.net.SocketConfig;

import java.io.Serializable;
import java.util.List;

@RequiredArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
public class RrpConfig extends SocketConfig {
    static final AttributeKey<RrpServer> ATTR_SVR = AttributeKey.valueOf("rSvr");
    static final AttributeKey<RrpServer.RpClient> ATTR_CLI = AttributeKey.valueOf("rCli");
    static final AttributeKey<RrpServer.RpClientProxy> ATTR_CLI_PROXY = AttributeKey.valueOf("rCliProxy");
    static final AttributeKey<ChannelFuture> ATTR_CONN_FUTURE = AttributeKey.valueOf("rConnF");

    private static final long serialVersionUID = -6857176126072816204L;
    public static final byte ACTION_REGISTER = 1;
    public static final byte ACTION_FORWARD = 2;

    @Data
    public static class Proxy implements Serializable {
        private static final long serialVersionUID = 6037910788987887824L;
        String name;
        // 1 = socks5
        int type;
        int remotePort;
    }

    String token;

    //server
    Integer bindPort;

    //client
    String serverEndpoint;
    boolean enableReconnect = true;
    long waitConnectMillis = 4000;
    List<Proxy> proxies;
}
