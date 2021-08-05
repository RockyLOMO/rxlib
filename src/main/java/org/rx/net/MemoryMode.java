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
    private AdaptiveRecvByteBufAllocator tcpRecvByteBufAllocator, udpRecvByteBufAllocator;
    private WriteBufferWaterMark waterMark;

    public AdaptiveRecvByteBufAllocator adaptiveRecvByteBufAllocator(boolean isUdp) {
        if (isUdp) {
            if (udpRecvByteBufAllocator == null) {
                udpRecvByteBufAllocator = new AdaptiveRecvByteBufAllocator(receiveBufInitial, receiveBufInitial, receiveBufMaximum);
            }
            return udpRecvByteBufAllocator;
        }

        if (tcpRecvByteBufAllocator == null) {
            tcpRecvByteBufAllocator = new AdaptiveRecvByteBufAllocator(receiveBufInitial / 16, receiveBufInitial, receiveBufMaximum);
        }
        return tcpRecvByteBufAllocator;
    }

    public WriteBufferWaterMark writeBufferWaterMark() {
        if (waterMark == null) {
            waterMark = new WriteBufferWaterMark(sendBufHighWaterMark / 2, sendBufHighWaterMark);
        }
        return waterMark;
    }
}
