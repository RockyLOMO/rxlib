package org.rx.io;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.rx.core.Constants;

/**
 * log 1G
 * index 12G
 */
@RequiredArgsConstructor
@Data
public class KeyValueStoreConfig {
    public static final String DEFAULT_DIRECTORY = "./data/def";

    public static KeyValueStoreConfig defaultConfig() {
        return miniConfig(DEFAULT_DIRECTORY);
    }

    public static KeyValueStoreConfig miniConfig(String directoryPath) {
        KeyValueStoreConfig conf = new KeyValueStoreConfig(directoryPath);
        //init 1G
        conf.setLogGrowSize(Constants.MB * 256);
        conf.setIndexGrowSize(Constants.MB * 4);
        return conf;
    }

    private final String directoryPath;
    /**
     * init big file for sequential write
     */
    private long logGrowSize = Constants.MB * 512;
    /**
     * The magnetic hard disk head needs to seek the next read position (taking about 5ms) for each thread.
     * Thus, reading with multiple threads effectively bounces the disk between seeks, slowing it down.
     * The only recommended way to read a file from a single disk is to read sequentially with one thread.
     */
    private int logReaderCount = 1;
    private int iteratorPrefetchCount = 2;

    private int indexSlotSize = Constants.MB * 128; //128M
    private int indexGrowSize = Constants.MB * 64; //32M

    private long writeBehindDelayed = 1000;
    private int writeBehindHighWaterMark = 8;

    private int apiPort = -1;
    private String apiPassword;
    private boolean apiSsl;
    private boolean apiReturnJson;
}
