package org.rx.net;

import io.netty.util.NetUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum MemoryMode {
    LOW(NetUtil.SOMAXCONN, 1024 * 64, 1024 * 32, 1024 * 64),
    MEDIUM(NetUtil.SOMAXCONN * 4, 1024 * 64 * 4, 1024 * 32 * 4, 1024 * 64 * 4),
    HIGH(NetUtil.SOMAXCONN * 8, 1024 * 64 * 8, 1024 * 32 * 8, 1024 * 64 * 8);

    private final int backlog;
    private final int receiveBufMaximum;
    private final int sendBufLowWaterMark, sendBufHighWaterMark;
}
