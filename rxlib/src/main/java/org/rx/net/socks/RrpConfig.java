package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.AttributeKey;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.rx.bean.Tuple;
import org.rx.net.SocketConfig;
import org.rx.net.transport.core.TransportSession;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = {"token", "nodeSecret"})
public class RrpConfig extends SocketConfig {
    static final AttributeKey<RrpServer> ATTR_SVR = AttributeKey.valueOf("rSvr");
    static final AttributeKey<RrpServer.RpClient> ATTR_SVR_CLI = AttributeKey.valueOf("rCli");
    static final AttributeKey<RrpServer.RpClientProxy> ATTR_SVR_PROXY = AttributeKey.valueOf("rSvrProxy");
    static final AttributeKey<Tuple<RrpClient.RpClientProxy, String>> ATTR_CLI_PROXY = AttributeKey.valueOf("rCliProxy");
    static final AttributeKey<ChannelFuture> ATTR_CLI_CONN = AttributeKey.valueOf("rCliConn");

    private static final long serialVersionUID = -6857176126072816205L;

    // SOCKS5 reverse proxy mode actions
    public static final byte ACTION_REGISTER = 1;
    public static final byte ACTION_FORWARD = 2;
    public static final byte ACTION_SYNC_CLOSE = 3;
    public static final byte ACTION_HEARTBEAT = 4;

    // NodeRegistry-authorized RAW logical stream Relay actions (v2)
    public static final byte ACTION_RAW_AUTH = 10;
    public static final byte ACTION_RAW_AUTH_ACK = 11;
    public static final byte ACTION_RAW_OPEN = 12;
    public static final byte ACTION_RAW_OPEN_INCOMING = 13;
    public static final byte ACTION_RAW_OPEN_ACK = 14;
    public static final byte ACTION_RAW_DATA = 15;
    public static final byte ACTION_RAW_HALF_CLOSE = 16;
    public static final byte ACTION_RAW_CLOSE = 17;
    public static final byte ACTION_RAW_PAUSE = 18;
    public static final byte ACTION_RAW_RESUME = 19;

    // RAW status / close reason codes
    public static final byte RAW_STATUS_OK = 0;
    public static final byte RAW_STATUS_UNAUTHORIZED = 1;
    public static final byte RAW_STATUS_TARGET_OFFLINE = 2;
    public static final byte RAW_STATUS_SERVICE_NOT_FOUND = 3;
    public static final byte RAW_STATUS_LIMIT_EXCEEDED = 4;
    public static final byte RAW_STATUS_TIMEOUT = 5;
    public static final byte RAW_STATUS_REJECTED = 6;
    public static final byte RAW_STATUS_DISABLED = 7;

    public static final byte RAW_CLOSE_NORMAL = 0;
    public static final byte RAW_CLOSE_OVERFLOW = 1;
    public static final byte RAW_CLOSE_PEER_DISCONNECTED = 2;
    public static final byte RAW_CLOSE_REVOKED = 3;
    public static final byte RAW_CLOSE_PROTOCOL_ERROR = 4;
    public static final byte RAW_CLOSE_UNKNOWN_STREAM = 5;

    static void writeHeartbeat(TransportSession session) {
        if (session == null || !session.isConnected() || !session.isWritable()) {
            return;
        }
        ByteBuf buf = session.channel().alloc().ioBuffer(1, 1);
        buf.writeByte(ACTION_HEARTBEAT);
        session.sendAsync(buf);
    }

    static void writeHeartbeat(Channel channel) {
        if (channel == null || !channel.isActive() || !channel.isWritable()) {
            return;
        }
        ByteBuf buf = channel.alloc().ioBuffer(1, 1);
        buf.writeByte(ACTION_HEARTBEAT);
        channel.writeAndFlush(buf).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    @Data
    @ToString(exclude = {"auth"})
    public static class Proxy implements Serializable {
        private static final long serialVersionUID = 6037910788987887824L;
        String name;
        int remotePort;
        String auth;
    }

    String token;

    // server
    Integer bindPort;

    // client
    String serverEndpoint;
    boolean enableReconnect = true;
    int waitConnectMillis = 4000;
    int heartbeatSeconds = 25;
    List<Proxy> proxies;

    // NodeRegistry-backed RAW Relay client identity (never logged)
    String nodeId;
    String nodeSecret;
    Set<String> rawServices;

    // RAW Relay server & client multi-tier resource budgets (conservative defaults)
    boolean enableRawRelay = true;
    int rawOpenTimeoutMillis = 5000;
    int maxGlobalRawStreams = 1024;
    int maxRawStreamsPerTenant = 512;
    int maxRawStreamsPerNode = 128;
    long maxGlobalRelayPendingBytes = 16L * 1024 * 1024;
    long maxTenantRelayPendingBytes = 8L * 1024 * 1024;
    long maxNodeRelayPendingBytes = 2L * 1024 * 1024;
    int maxStreamRelayPendingBytes = 512 * 1024;
    int streamPauseHighWaterBytes = 256 * 1024;
    int streamResumeLowWaterBytes = 64 * 1024;
    int maxRawFrameBytes = 64 * 1024;
}
