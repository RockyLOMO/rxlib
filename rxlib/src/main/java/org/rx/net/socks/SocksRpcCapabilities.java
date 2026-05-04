package org.rx.net.socks;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
public final class SocksRpcCapabilities implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final int UDP_RELAY_GROUP = 1;
    public static final int UDP_RELAY_GROUP_ADD = 1 << 1;
    public static final int UDP_RELAY_GROUP_HEARTBEAT = 1 << 2;
    public static final int UDP_RELAY_GROUP_SHARED_DEDUP = 1 << 3;
    public static final int UDP_RELAY_GROUP_BATCH_RESET = 1 << 4;
    public static final int UDP2RAW_TUNNEL = 1 << 5;

    public static final SocksRpcCapabilities EMPTY = new SocksRpcCapabilities();

    private int flags;
    private int version = 1;
    private int maxRelaysPerGroup;
    private int maxGroups;
    private int maxUdp2rawSessions;
    private boolean udp2rawFixedEntry;

    public SocksRpcCapabilities() {
    }

    public SocksRpcCapabilities(int flags, int maxRelaysPerGroup, int maxGroups) {
        this.flags = flags;
        this.maxRelaysPerGroup = maxRelaysPerGroup;
        this.maxGroups = maxGroups;
    }

    public boolean has(int flag) {
        return (flags & flag) == flag;
    }

    public void addFlag(int flag) {
        flags |= flag;
    }

    public static SocksRpcCapabilities udpRelayGroup(int maxRelaysPerGroup, int maxGroups) {
        return new SocksRpcCapabilities(UDP_RELAY_GROUP | UDP_RELAY_GROUP_ADD | UDP_RELAY_GROUP_HEARTBEAT,
                maxRelaysPerGroup, maxGroups);
    }
}
