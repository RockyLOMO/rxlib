package org.rx.net.transport.hybrid;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;

@Getter
@RequiredArgsConstructor
public final class HybridSendResult {
    private final HybridRoute selectedRoute;
    private final HybridRoute actualRoute;
    private final long sequence;
    private final int encodedBytes;
    private final int udpFragmentCount;
    private final CompletableFuture<Void> writeFuture;
    private final CompletableFuture<Void> ackFuture;
    private final boolean cancelled;
}
