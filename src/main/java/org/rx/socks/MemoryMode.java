package org.rx.socks;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum MemoryMode {
    Low(128, 16 * 1024, 16 * 1024, 64 * 1024, 128 * 1024),
    Medium(512, 64 * 1024, 64 * 1024, 512 * 1024, 1024 * 1024),
    High(1024, 128 * 1024, 128 * 1024, 8 * 1024 * 1024, 16 * 1024 * 1024);

    private final int backlog;
    private final int sendBuf, receiveBuf;
    private final int lowWaterMark, highWaterMark;
}
