package org.rx.core;

import lombok.SneakyThrows;
import org.rx.core.exception.InvalidException;

import java.util.concurrent.TimeoutException;

import static org.rx.core.App.TIMEOUT_INFINITE;

public final class ManualResetEvent {
    //    private final Object monitor = new Object();
    private final Object monitor = this;
    private volatile boolean open;

    public ManualResetEvent() {
        this(false);
    }

    public ManualResetEvent(boolean initialState) {
        this.open = initialState;
    }

    @SneakyThrows
    public void waitOne() {
        waitOne(TIMEOUT_INFINITE);
    }

    public void waitOne(long timeout) throws TimeoutException {
        timeout = timeout == TIMEOUT_INFINITE ? 0 : timeout;
        synchronized (monitor) {
            while (!open) {
                try {
                    monitor.wait(timeout);
                } catch (InterruptedException e) {
                    //ignore
                    throw InvalidException.sneaky(e);
                }
                if (timeout > 0) {
                    if (!open) {
                        throw new TimeoutException("Call waitOne() time out");
                    }
                    break;
                }
            }
        }
    }

    public void set() {
        synchronized (monitor) {
            open = true;
            monitor.notifyAll();
        }
    }

    public void reset() {//close stop
        open = false;
    }
}
