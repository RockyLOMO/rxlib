package org.rx.io;

import io.netty.util.Timeout;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.rx.bean.IntWaterMark;
import org.rx.core.Disposable;
import org.rx.core.RunFlag;
import org.rx.core.Tasks;
import org.rx.util.RedoTimer;
import org.rx.util.function.Action;

import java.util.Map;
import java.util.TreeMap;

import static org.rx.core.App.quietly;

final class WriteBackQueue extends Disposable {
    @Setter
    long delaySavePeriod = 500;
    @Getter
    private final IntWaterMark waterMark = new IntWaterMark(8, 16);
    private final TreeMap<Long, Action> sortMap = new TreeMap<>();
    private final RedoTimer timer = new RedoTimer();
    private Timeout timeout;

    @Override
    protected void freeObjects() {
        consume();
    }

    @SneakyThrows
    public synchronized void offer(long position, Action writeAction) {
        checkNotClosed();

        if (timeout != null) {
            timeout.cancel();
        }

        sortMap.put(position, writeAction);
        if (sortMap.size() > waterMark.getHigh()) {
            Tasks.run((Action) this::consume, toString(), RunFlag.SINGLE);
            wait();
            return;
        }

        timeout = timer.setTimeout(this::consume, delaySavePeriod);
    }

    public void consume() {
        consume(timeout);
    }

    private void consume(Timeout t) {
        if (t != null) {
            t.cancel();
        }

        while (true) {
            Map.Entry<Long, Action> entry;
            synchronized (this) {
                entry = sortMap.pollFirstEntry();
                if (sortMap.size() <= waterMark.getLow()) {
                    notifyAll();
                }
            }
            if (entry == null) {
                break;
            }
            quietly(entry.getValue());
        }
    }
}
