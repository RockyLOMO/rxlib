package org.rx.io;

import org.rx.core.Arrays;
import org.rx.core.Constants;

public interface Serializer {
    Serializer DEFAULT = new JdkAndJsonSerializer();

    default <T> byte[] serializeToBytes(T[] obj) {
        if (Arrays.isEmpty(obj)) {
            return Arrays.EMPTY_BYTE_ARRAY;
        }
        try (HybridStream stream = new HybridStream()) {
            for (T t : obj) {
                serialize(t, stream);
            }
            return stream.toArray();
        }
    }

    default <T> byte[] serializeToBytes(T obj) {
        try (IOStream stream = serialize(obj)) {
            return stream.toArray();
        }
    }

    default <T> IOStream serialize(T obj) {
        return serialize(obj, Constants.MAX_HEAP_BUF_SIZE, false);
    }

    default <T> HybridStream serialize(T obj, int maxMemorySize, boolean directMemory) {
        HybridStream stream = new HybridStream(maxMemorySize, directMemory, null);
        serialize(obj, stream);
        return stream.rewind();
    }

    <T> void serialize(T obj, IOStream stream);

    default <T> T deserializeFromBytes(byte[] data) {
        return deserialize(IOStream.wrap("", data));
    }

    default <T> T deserialize(IOStream stream) {
        return deserialize(stream, false);
    }

    <T> T deserialize(IOStream stream, boolean leveOpen);
}
