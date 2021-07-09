package org.rx.io;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Data
public class KeyValueStoreConfig {
    public static final String DEFAULT_DIRECTORY = "./data";
    static final int OneM = 1024 * 1024;

    public static KeyValueStoreConfig defaultConfig() {
        KeyValueStoreConfig conf = new KeyValueStoreConfig(DEFAULT_DIRECTORY);
        conf.setLogGrowSize(OneM * 512);
        conf.setIndexGrowSize(OneM * 16);
        return conf;
    }

    private final String directoryPath;
    /**
     * init big file for sequential write
     */
    private long logGrowSize = OneM * 1024; //1G
    /**
     * The magnetic hard disk head needs to seek the next read position (taking about 5ms) for each thread.
     * Thus, reading with multiple threads effectively bounces the disk between seeks, slowing it down.
     * The only recommended way to read a file from a single disk is to read sequentially with one thread.
     */
    private int logReaderCount = 1;

    private int indexSlotSize = OneM * 128; //128M
    private int indexGrowSize = OneM * 32; //32M
}
