package org.rx.bean;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

//节省内存
public abstract class AbstractReferenceCounter {
    protected static final AtomicIntegerFieldUpdater<AbstractReferenceCounter> updater = AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCounter.class, "refCnt");

    protected volatile int refCnt;

    public void xxx() {
        updater.incrementAndGet(this);
    }
}
