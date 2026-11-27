package org.rx.net.rpc;

import lombok.Getter;
import org.rx.net.transport.core.TransportServer;

import java.net.InetSocketAddress;

@Getter
public final class RemotingClientHandle {
    private final transient TransportServer server;
    private final long sessionId;
    private final String peerId;
    private final InetSocketAddress remoteEndpoint;

    RemotingClientHandle(TransportServer server, long sessionId, String peerId, InetSocketAddress remoteEndpoint) {
        this.server = server;
        this.sessionId = sessionId;
        this.peerId = peerId;
        this.remoteEndpoint = remoteEndpoint;
    }
}
