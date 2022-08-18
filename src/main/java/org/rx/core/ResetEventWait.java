package org.rx.core;

import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.rx.exception.InvalidException;

import java.util.concurrent.TimeoutException;

import static org.rx.core.Constants.TIMEOUT_INFINITE;

//synchronized 没有TimeoutException
public final class ResetEventWait {
    public static TimeoutException newTimeoutException(String message, Throwable e) {
        StringBuilder buf = new StringBuilder(message);
        if (e != null) {
            buf.append("\n").append(ExceptionUtils.getStackTrace(e));
            System.out.println(111);
        }
        return new TimeoutException(buf.toString());
    }

    private volatile boolean open;
    @Getter
    private int holdCount;

    public ResetEventWait() {
        this(false);
    }

    public ResetEventWait(boolean initialState) {
        this.open = initialState;
    }

    @SneakyThrows
    public void waitOne() {
        waitOne(TIMEOUT_INFINITE);
    }

    public synchronized void waitOne(long timeout) throws TimeoutException {
        timeout = timeout == TIMEOUT_INFINITE ? 0 : timeout;
        while (!open) {
            try {
                holdCount++;
                wait(timeout);
            } catch (InterruptedException e) {
                //ignore
                throw InvalidException.sneaky(e);
            } finally {
                holdCount--;
            }
            if (timeout > 0) {
                if (!open) {
                    throw new TimeoutException("Wait unpark timeout");
                }
                break;
            }
        }
    }

    public synchronized void set() {
        open = true;
        notifyAll();
    }

    public void reset() {//close stop
        open = false;
    }
}
