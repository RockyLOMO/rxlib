package org.rx.cache;

import java.util.function.Consumer;

import static org.rx.core.Contract.require;

public class BufferSegment {
    private final byte[] buffer;
    private final int bufferSize;
    private volatile int offset;
    private volatile boolean autoReleased;

    public boolean isAutoReleased() {
        return autoReleased;
    }

    public void setAutoReleased(boolean autoReleased) {
        this.autoReleased = autoReleased;
    }

    public BufferSegment(int bufferSize, int bufferCount) {
        require(bufferSize, bufferSize >= 0);
        require(bufferCount, bufferCount >= 0);

        buffer = new byte[(this.bufferSize = bufferSize) * bufferCount];
    }

    public BytesSegment alloc() {
        if (offset == buffer.length) {
            return new BytesSegment(new byte[bufferSize]);
        }

        synchronized (buffer) {
            BytesSegment segment = new BytesSegment(buffer, offset, bufferSize);
            offset += bufferSize;
            if (autoReleased) {
                segment.Closed = p -> release(p);
            }
            return segment;
        }
    }

    public void release(BytesSegment segment) {
        require(segment);

        if (buffer != segment.array) {
            return;
        }

        synchronized (buffer) {
            offset -= segment.count;
        }
    }

    public void accept(Consumer<BytesSegment> consumer) {
        require(consumer);

        try (BytesSegment segment = alloc()) {
            consumer.accept(segment);
        }
    }
}
