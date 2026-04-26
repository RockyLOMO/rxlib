package org.rx.net.transport;

import io.netty.channel.ChannelFuture;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;

@Getter
@RequiredArgsConstructor
public final class UdpSendResult {
    private final ChannelFuture writeFuture;
    private final CompletableFuture<Void> ackFuture;
    private final int encodedBytes;
    private final int fragmentCount;
}
