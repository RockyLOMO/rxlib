package org.rx.net.transport.protocol;

import lombok.RequiredArgsConstructor;

import java.io.Serializable;

@RequiredArgsConstructor
public final class UdpRpcResponse implements Serializable {
    private static final long serialVersionUID = 5195184369957156237L;
    public final int requestId;
    public final Serializable value;
    public final Throwable error;

    public static UdpRpcResponse success(int requestId, Serializable value) {
        return new UdpRpcResponse(requestId, value, null);
    }

    public static UdpRpcResponse error(int requestId, Throwable error) {
        return new UdpRpcResponse(requestId, null, error);
    }
}
