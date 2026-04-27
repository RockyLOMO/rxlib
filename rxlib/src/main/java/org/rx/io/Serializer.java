package org.rx.io;

import org.rx.core.Arrays;
import org.rx.core.Constants;

public interface Serializer {
    Serializer DEFAULT = new JdkAndJsonSerializer();
    Serializer FURY = new FurySerializer();

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
        try (DuplexStream stream = serialize(obj)) {
            return stream.toArray();
        }
    }

    default <T> DuplexStream serialize(T obj) {
        return serialize(obj, Constants.MAX_HEAP_BUF_SIZE, false);
    }

    default <T> HybridStream serialize(T obj, int maxMemorySize, boolean directMemory) {
        HybridStream stream = new HybridStream(maxMemorySize, directMemory);
        serialize(obj, stream);
        return stream.rewind();
    }

    <T> void serialize(T obj, DuplexStream stream);

    default <T> T deserializeFromBytes(byte[] data) {
        return deserialize(DuplexStream.wrap("", data));
    }

    default <T> T deserialize(DuplexStream stream) {
        return deserialize(stream, false);
    }

    <T> T deserialize(DuplexStream stream, boolean leveOpen);
}
