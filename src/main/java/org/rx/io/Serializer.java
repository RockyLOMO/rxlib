package org.rx.io;

public interface Serializer<T> {
    Serializer DEFAULT = new JdkAndJsonSerializer<>();

    void serialize(T obj, IOStream<?, ?> stream);

    T deserialize(IOStream<?, ?> stream);
}
