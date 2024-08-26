package org.rx.bean;

import lombok.Getter;
import org.rx.core.*;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

public class CircularBlockingQueue<T> extends LinkedBlockingQueue<T> implements EventPublisher<CircularBlockingQueue<T>> {
    private static final long serialVersionUID = 4685018531330571106L;
    public final Delegate<CircularBlockingQueue<T>, T> onConsume = Delegate.create();
    public final Delegate<CircularBlockingQueue<T>, NEventArgs<T>> onFull = Delegate.create();
    final ReentrantLock pLock = Reflects.readField(this, "putLock");
    TimeoutFuture<?> consumeTimer;
    @Getter
    long consumePeriod;

    public void setCapacity(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException();
        Reflects.writeField(this, "capacity", capacity);
    }

    public int getCapacity() {
        return Reflects.readField(this, "capacity");
    }

    public synchronized void setConsumePeriod(long consumePeriod) {
        if ((this.consumePeriod = consumePeriod) > 0) {
            if (consumeTimer != null) {
                consumeTimer.cancel();
            }
            consumeTimer = Tasks.timer().setTimeout(() -> {
                T t;
                while ((t = poll()) != null) {
                    raiseEvent(onConsume, t);
                }
            }, d -> consumePeriod, null, Constants.TIMER_PERIOD_FLAG);
        } else {
            if (consumeTimer != null) {
                consumeTimer.cancel();
            }
        }
    }

    public CircularBlockingQueue(int capacity) {
        super(capacity);
        onFull.combine((q, t) -> {
            pLock.lock();
            try {
                boolean ok;
                do {
                    q.poll();
                    ok = q.innerOffer(t.getValue());
                }
                while (!ok);
            } finally {
                pLock.unlock();
            }
        });
    }

    //Full会抛异常
//        @Override
//        public boolean add(T t) {
//            return offer(t);
//        }

    @Override
    public boolean offer(T t) {
        boolean r = super.offer(t);
        if (!r && onFull != null) {
            NEventArgs<T> e = new NEventArgs<>(t);
            raiseEvent(onFull, e);
            return !e.isCancel();
        }
        return r;
    }

    protected boolean innerOffer(T t) {
        return super.offer(t);
    }
}
