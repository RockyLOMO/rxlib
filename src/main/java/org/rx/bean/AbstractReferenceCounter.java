package org.rx.bean;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

//Save memory
public abstract class AbstractReferenceCounter {
    protected static final AtomicIntegerFieldUpdater<AbstractReferenceCounter> updater = AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCounter.class, "refCnt");

    protected volatile int refCnt;

    public int getRefCnt() {
        return updater.get(this);
    }

    public int incrementRefCnt() {
        return updater.incrementAndGet(this);
    }

    public int decrementRefCnt() {
        return updater.decrementAndGet(this);
    }
}
