package org.rx.common;

import org.rx.annotation.ErrorCode;

import static org.rx.common.Contract.values;

public abstract class Disposable implements AutoCloseable {
    private boolean closed;

    public boolean isClosed() {
        return closed;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        dispose();
    }

    private synchronized void dispose() {
        if (closed) {
            return;
        }
        freeObjects();
        closed = true;
    }

    protected abstract void freeObjects();

    @ErrorCode(messageKeys = { "$type" })
    protected void checkNotClosed() {
        if (closed) {
            throw new SystemException(values(this.getClass().getSimpleName()));
        }
    }

    @Override
    public void close() {
        dispose();
    }
}
