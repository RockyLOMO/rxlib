package org.rx.bean;

import lombok.Getter;
import lombok.Setter;
import org.rx.core.*;
import org.rx.util.function.TripleFunc;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

import static org.rx.core.Extends.ifNull;

public class CircularBlockingQueue<T> extends LinkedBlockingQueue<T> implements EventPublisher<CircularBlockingQueue<T>> {
    private static final long serialVersionUID = 4685018531330571106L;
    public final Delegate<CircularBlockingQueue<T>, NEventArgs<T>> onConsume = Delegate.create();
    public TripleFunc<CircularBlockingQueue<T>, T, Boolean> onFull;
    final ReentrantLock pLock = Reflects.readField(this, "putLock");
    TimeoutFuture<?> consumeTimer;
    @Getter
    long consumePeriod;

    public synchronized void setConsumePeriod(long consumePeriod) {
        if ((this.consumePeriod = consumePeriod) > 0) {
            if (consumeTimer != null) {
                consumeTimer.cancel();
            }
            consumeTimer = Tasks.timer().setTimeout(() -> {
                T t;
                NEventArgs<T> e = new NEventArgs<>();
                while ((t = poll()) != null) {
                    e.setValue(t);
                    raiseEvent(onConsume, e);
                }
            }, d -> consumePeriod, null, Constants.TIMER_PERIOD_FLAG);
        } else {
            if (consumeTimer != null) {
                consumeTimer.cancel();
            }
        }
    }

    public CircularBlockingQueue(int capacity) {
        this(capacity, null);
        onFull = (q, t) -> {
            pLock.lock();
            try {
                boolean ok;
                do {
                    q.poll();
                    ok = q.innerOffer(t);
                }
                while (!ok);
                return true;
            } finally {
                pLock.unlock();
            }
        };
    }

    public CircularBlockingQueue(int capacity, TripleFunc<CircularBlockingQueue<T>, T, Boolean> onFull) {
        super(capacity);
        this.onFull = onFull;
    }

//        @Override
//        public boolean add(T t) {
//            return offer(t);
//        }

    @Override
    public boolean offer(T t) {
        boolean r = super.offer(t);
        if (!r && onFull != null) {
            return ifNull(onFull.apply(this, t), false);
        }
        return r;
    }

    protected boolean innerOffer(T t) {
        return super.offer(t);
    }
}
