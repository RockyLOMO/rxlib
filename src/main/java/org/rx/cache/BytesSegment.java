package org.rx.cache;

import java.util.function.Consumer;

import static org.rx.common.Contract.require;

public final class BytesSegment implements AutoCloseable {
    public Consumer<BytesSegment> Closed;
    public final byte[] array;
    public final int offset, count;

    public byte[] getArray() {
        return array;
    }

    public int getOffset() {
        return offset;
    }

    public int getCount() {
        return count;
    }

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
