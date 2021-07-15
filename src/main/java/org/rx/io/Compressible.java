package org.rx.io;

import java.io.Serializable;

public interface Compressible extends Serializable {
    default boolean enableCompress() {
        return true;
    }
}
