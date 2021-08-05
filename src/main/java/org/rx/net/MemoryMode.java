package org.rx.net;

import io.netty.channel.AdaptiveRecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.net.socks.SocksConfig;

@RequiredArgsConstructor
@Getter
public enum MemoryMode {
    LOW(1024, SocksConfig.BUF_SIZE_4K, SocksConfig.BUF_SIZE_4K * 1024, SocksConfig.BUF_SIZE_4K * 1024),
    MEDIUM(2048, LOW.receiveBufInitial * 4, LOW.receiveBufMaximum * 4, LOW.sendBufHighWaterMark * 4),
    HIGH(4096, MEDIUM.receiveBufInitial * 4, MEDIUM.receiveBufMaximum * 4, MEDIUM.sendBufHighWaterMark * 4);

    private final int backlog;
    private final int receiveBufInitial, receiveBufMaximum;
    private final int sendBufHighWaterMark;

    public AdaptiveRecvByteBufAllocator adaptiveRecvByteBufAllocator(boolean isUdp) {
        int initial = receiveBufInitial;
        int minimum = isUdp ? initial : initial / 16;
        return new AdaptiveRecvByteBufAllocator(minimum, initial, receiveBufMaximum);
    }

    public WriteBufferWaterMark writeBufferWaterMark() {
        return new WriteBufferWaterMark(sendBufHighWaterMark / 2, sendBufHighWaterMark);
    }
}
