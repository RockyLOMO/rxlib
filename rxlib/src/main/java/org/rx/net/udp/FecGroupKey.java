package org.rx.net.udp;

import lombok.Getter;

import java.net.InetSocketAddress;

/**
 * FEC 解码分组 key：sender + sessionId + groupId。
 */
@Getter
public final class FecGroupKey {
    private final InetSocketAddress sender;
    private final int sessionId;
    private final int groupId;

    public FecGroupKey(InetSocketAddress sender, int sessionId, int groupId) {
        this.sender = UdpResilienceAttributes.normalize(sender);
        this.sessionId = sessionId;
        this.groupId = groupId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FecGroupKey)) {
            return false;
        }
        FecGroupKey that = (FecGroupKey) o;
        if (sessionId != that.sessionId || groupId != that.groupId) {
            return false;
        }
        return sender != null ? sender.equals(that.sender) : that.sender == null;
    }

    @Override
    public int hashCode() {
        int result = sender != null ? sender.hashCode() : 0;
        result = 31 * result + sessionId;
        result = 31 * result + groupId;
        return result;
    }
}
