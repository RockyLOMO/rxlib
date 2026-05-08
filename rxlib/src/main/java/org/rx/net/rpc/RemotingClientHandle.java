package org.rx.net.rpc;

import lombok.Getter;
import org.rx.net.transport.hybrid.HybridServer;

import java.net.InetSocketAddress;

@Getter
public final class RemotingClientHandle {
    private final transient HybridServer server;
    private final long sessionId;
    private final String peerId;
    private final InetSocketAddress tcpRemoteEndpoint;

    RemotingClientHandle(HybridServer server, long sessionId, String peerId, InetSocketAddress tcpRemoteEndpoint) {
        this.server = server;
        this.sessionId = sessionId;
        this.peerId = peerId;
        this.tcpRemoteEndpoint = tcpRemoteEndpoint;
    }
}
