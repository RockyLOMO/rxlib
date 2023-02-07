package org.rx.core;

import lombok.Getter;
import org.rx.exception.InvalidException;

import static org.rx.core.Constants.TIMEOUT_INFINITE;

public final class ResetEventWait implements WaitHandle {
    private volatile boolean open;
    @Getter
    private volatile int holdCount;

    public ResetEventWait() {
        this(false);
    }

    public ResetEventWait(boolean initialState) {
        this.open = initialState;
    }

    public boolean waitOne() {
        return waitOne(TIMEOUT_INFINITE);
    }

    /**
     * Blocks the current thread until the current WaitHandle receives a signal, using a 32-bit signed integer to specify the time interval in milliseconds.
     *
     * @param timeoutMillis The number of milliseconds to wait, or Infinite (-1) to wait indefinitely.
     * @return true if the current instance receives a signal; otherwise, false.
     */
    public synchronized boolean waitOne(long timeoutMillis) {
        timeoutMillis = timeoutMillis == TIMEOUT_INFINITE ? 0 : timeoutMillis;
        while (!open) {
            try {
                holdCount++;
                wait(timeoutMillis);
            } catch (InterruptedException e) {
                //ignore
                throw InvalidException.sneaky(e);
            } finally {
                holdCount--;
            }
            if (timeoutMillis > 0) {
                if (!open) {
                    return false;
                }
                break;
            }
        }
        return true;
    }

    public synchronized void set() {
        open = true;
        notifyAll();
    }

    public synchronized void reset() {
        open = false;
    }

    @Override
    public boolean await(long timeoutMillis) {
        return waitOne(timeoutMillis);
    }

    @Override
    public void signal() {
        set();
    }
}
