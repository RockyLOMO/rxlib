package org.rx.net.socks;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.rx.net.support.UnresolvedEndpoint;

import java.net.InetSocketAddress;

@Getter
@Setter
@ToString(exclude = "authTag")
public final class Udp2rawFrame {
    private int version = Udp2rawCodec.VERSION;
    private int flags;
    private Udp2rawFrameType type = Udp2rawFrameType.DATA;
    private long sessionHi;
    private long sessionLo;
    private long connId;
    private long packetSeq;
    private InetSocketAddress clientSource;
    private UnresolvedEndpoint destination;
    private InetSocketAddress sourceAddress;
    private ByteBuf authTag;

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public Udp2rawSessionKey sessionKey() {
        return new Udp2rawSessionKey(sessionHi, sessionLo, connId);
    }

    public static Udp2rawFrame data(long sessionHi, long sessionLo, long connId, long packetSeq) {
        Udp2rawFrame frame = new Udp2rawFrame();
        frame.sessionHi = sessionHi;
        frame.sessionLo = sessionLo;
        frame.connId = connId;
        frame.packetSeq = packetSeq;
        return frame;
    }
}
