package org.rx.io;

import org.rx.bean.RxConfig;

public interface Serializer {
    Serializer DEFAULT = new JdkAndJsonSerializer();

    default <T> IOStream<?, ?> serialize(T obj) {
        return serialize(obj, RxConfig.MAX_HEAP_BUF_SIZE, null);
    }

    default <T> HybridStream serialize(T obj, int maxMemorySize, String tempFilePath) {
        HybridStream stream = new HybridStream(maxMemorySize, tempFilePath);
        serialize(obj, stream);
        return stream;
    }

    <T> void serialize(T obj, IOStream<?, ?> stream);

    default <T> T deserialize(IOStream<?, ?> stream) {
        return deserialize(stream, false);
    }

    <T> T deserialize(IOStream<?, ?> stream, boolean leveOpen);
}
