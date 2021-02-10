package org.rx.net;

import lombok.Getter;

import java.util.function.Consumer;

import static org.rx.core.App.require;

public final class BytesSegment implements AutoCloseable {
    public Consumer<BytesSegment> Closed;
    @Getter
    public final byte[] array;
    @Getter
    public final int offset, count;

    public BytesSegment(byte[] array) {
        this(array, 0, array.length);
    }

    public BytesSegment(byte[] array, int offset, int count) {
        require(array);
        require(offset, offset >= 0);
        require(count, count >= 0);

        this.array = array;
        this.offset = offset;
        this.count = count;
    }

    @Override
    public void close() {
        if (Closed != null) {
            Closed.accept(this);
        }
    }
}
