package org.rx.io;

import org.rx.core.Constants;

public interface Serializer {
    Serializer DEFAULT = new JdkAndJsonSerializer();

    default <T> byte[] serializeToBytes(T obj) {
        try (IOStream<?, ?> stream = serialize(obj)) {
            return stream.toArray();
        }
    }

    default <T> IOStream<?, ?> serialize(T obj) {
        return serialize(obj, Constants.MAX_HEAP_BUF_SIZE, null);
    }

    default <T> HybridStream serialize(T obj, int maxMemorySize, String tempFilePath) {
        HybridStream stream = new HybridStream(maxMemorySize, tempFilePath);
        serialize(obj, stream);
        return (HybridStream) stream.rewind();
    }

    <T> void serialize(T obj, IOStream<?, ?> stream);

    default <T> T deserialize(IOStream<?, ?> stream) {
        return deserialize(stream, false);
    }

    <T> T deserialize(IOStream<?, ?> stream, boolean leveOpen);
}
