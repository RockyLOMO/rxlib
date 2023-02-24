package org.rx.io;

import lombok.*;
import org.rx.core.Constants;

/**
 * log 1G
 * index 12G
 */
@RequiredArgsConstructor
@Getter
@Setter
@ToString
public class KeyValueStoreConfig {
    public static <TK, TV> KeyValueStoreConfig newConfig(Class<TK> keyType, Class<TV> valueType) {
        KeyValueStoreConfig conf = new KeyValueStoreConfig(keyType, valueType);
        conf.setLogGrowSize(Constants.MB * 16);
        conf.setIndexBufferSize(Constants.MB);
        return conf;
    }

    private final Class<?> keyType;
    private final Class<?> valueType;
    private String directoryPath = "./data/";
    /**
     * init big file for sequential write
     */
    private long logGrowSize = Constants.MB * 1024;
    /**
     * The magnetic hard disk head needs to seek the next read position (taking about 5ms) for each thread.
     * Thus, reading with multiple threads effectively bounces the disk between seeks, slowing it down.
     * The only recommended way to read a file from a single disk is to read sequentially with one thread.
     */
    private int logReaderCount = 1;
    private long flushDelayMillis = 1000;
    private int iteratorPrefetchCount = 2;

    private int indexBufferSize = Constants.MB * 64;
    private int indexReaderCount = 1;

    private int apiPort = -1;
    private String apiPassword;
    private boolean apiSsl;
    private boolean apiReturnJson;
}
