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

    protected abstract void dispose() throws Throwable;

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
        dispose();
        closed = true;
    }

    //java 9 不建议
//    @Override
//    protected void finalize() throws Throwable {
//        try {
//            close();
//        } finally {
//            super.finalize();
//        }
//    }
}
