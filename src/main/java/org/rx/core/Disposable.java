package org.rx.core;

import org.rx.annotation.ErrorCode;
import org.rx.core.exception.ApplicationException;

import static org.rx.core.App.values;

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
        closed = true;
        freeObjects();
    }

    protected abstract void freeObjects();

    @ErrorCode
    protected void checkNotClosed() {
        if (closed) {
            throw new ApplicationException(values(this.getClass().getSimpleName()));
        }
    }

    @Override
    public void close() {
        dispose();
    }
}
