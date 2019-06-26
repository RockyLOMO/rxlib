package org.rx.util;

import lombok.SneakyThrows;
import org.rx.common.App;

import java.util.concurrent.TimeoutException;

public final class ManualResetEvent {
    private final Object monitor = new Object();
    private volatile boolean open;

    public ManualResetEvent() {
        this(false);
    }

    public ManualResetEvent(boolean initialState) {
        this.open = initialState;
    }

    @SneakyThrows
    public void waitOne() {
        waitOne(App.TimeoutInfinite);
    }

    @SneakyThrows
    public void waitOne(long timeout) throws TimeoutException {
        timeout = timeout == App.TimeoutInfinite ? 0 : timeout;
        synchronized (monitor) {
            while (!open) {
                monitor.wait(timeout);
                if (timeout > 0) {
                    if (!open) {
                        throw new TimeoutException("Call waitOne() time out");
                    }
                    break;
                }
            }
        }
    }

    public void set() {//open start
        synchronized (monitor) {
            open = true;
            monitor.notifyAll();
        }
    }

    public void reset() {//close stop
        open = false;
    }
}
