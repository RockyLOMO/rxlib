package org.rx.io;

import java.io.Serializable;

public interface Compressible extends Serializable {
    short STREAM_MAGIC = -21266;
    short STREAM_VERSION = 1;
    int MIN_LENGTH = 1000;

    default boolean enableCompress() {
        return true;
    }
}
