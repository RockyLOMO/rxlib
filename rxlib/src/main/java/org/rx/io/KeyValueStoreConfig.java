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
    public interface DirPaths {
        String DEFAULT = "./data/def";
        String SOCKS = "./data/socks";
        String HOST = "./data/host";
    }

    public static KeyValueStoreConfig defaultConfig() {
        return defaultConfig(DirPaths.DEFAULT);
    }

    public static KeyValueStoreConfig defaultConfig(String directoryPath) {
        KeyValueStoreConfig conf = new KeyValueStoreConfig(directoryPath);
        conf.setLogGrowSize(Constants.MB * 64);
        conf.setIndexSlotSize(Constants.MB * 512);
        conf.setIndexGrowSize(Constants.MB * 2);
        return conf;
    }

    private final String filePath;
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
    private int indexGrowSize = Constants.MB * 32; //32M

    private long writeBehindDelayed = 1000;
    private int writeBehindHighWaterMark = 8;

    private int apiPort = -1;
    private String apiPassword;
    private boolean apiSsl;
    private boolean apiReturnJson;
}
