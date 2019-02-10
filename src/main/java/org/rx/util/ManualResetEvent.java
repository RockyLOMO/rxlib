package org.rx.util;

import lombok.SneakyThrows;

public final class ManualResetEvent {
    private final Object monitor = new Object();
    private volatile boolean open;

    public ManualResetEvent() {
        this(false);
    }

    public ManualResetEvent(boolean initialState) {
        this.open = initialState;
    }

    public void waitOne() {
        waitOne(0);
    }

    @SneakyThrows
    public void waitOne(long timeout) {
        synchronized (monitor) {
            while (!open) {
                monitor.wait(timeout);
                if (timeout > 0) {
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
