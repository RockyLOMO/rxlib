package org.rx.common;

import lombok.SneakyThrows;

public final class ManualResetEvent {
    private final Object monitor = new Object();
    private volatile boolean open;

    public ManualResetEvent(boolean initialState) {
        this.open = initialState;
    }

    @SneakyThrows
    public void waitOne() {
        synchronized (monitor) {
            while (!open) {
                monitor.wait();
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
