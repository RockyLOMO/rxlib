package org.rx.core;

import lombok.SneakyThrows;
import org.rx.annotation.ErrorCode;
import org.rx.exception.ApplicationException;

import static org.rx.core.Extends.values;

public abstract class Disposable implements AutoCloseable {
    private volatile boolean closed;

    public boolean isClosed() {
        return closed;
    }

    protected abstract void freeObjects() throws Throwable;

    @ErrorCode
    protected void checkNotClosed() {
        if (closed) {
            throw new ApplicationException(values(this.getClass().getSimpleName()));
        }
    }

    @SneakyThrows
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        //todo fields may be null
        freeObjects();
        closed = true;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }
}
