package org.rx;

public abstract class Disposable implements AutoCloseable {
    private boolean closed;

    public boolean isClosed() {
        return closed;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        dispose(false);
    }

    private synchronized void dispose(boolean disposing) {
        if (closed) {
            return;
        }
        if (disposing) {
            freeManaged();
        }
        freeUnmanaged();
        closed = true;
    }

    protected void freeManaged() {
    }

    protected abstract void freeUnmanaged();

    @Override
    public void close() {
        dispose(true);
    }
}
