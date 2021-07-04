package org.rx.io;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Data
public class KeyValueStoreConfig {
    private final String directoryPath;
    private long logGrowSize = 1024 * 1024 * 1024; //1G
    /**
     * The magnetic hard disk head needs to seek the next read position (taking about 5ms) for each thread.
     * Thus, reading with multiple threads effectively bounces the disk between seeks, slowing it down.
     * The only recommended way to read a file from a single disk is to read sequentially with one thread.
     */
    private int logReaderCount = 1;

    private int indexFileMaxSize = 1024 * 1024 * 128; //128M
}
