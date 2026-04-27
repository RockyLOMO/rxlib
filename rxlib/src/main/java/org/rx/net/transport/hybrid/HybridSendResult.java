package org.rx.net.transport.hybrid;

import lombok.Getter;
import lombok.AllArgsConstructor;
import lombok.Setter;

import java.util.concurrent.CompletableFuture;

@Getter
@AllArgsConstructor
public final class HybridSendResult {
    private final HybridRoute selectedRoute;
    @Setter
    private volatile HybridRoute actualRoute;
    private final long sequence;
    private final int encodedBytes;
    private final int udpFragmentCount;
    private final CompletableFuture<Void> writeFuture;
    private final CompletableFuture<Void> ackFuture;
    private final boolean cancelled;
}
