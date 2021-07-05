package org.rx.io;

import io.netty.util.Timeout;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.IntWaterMark;
import org.rx.core.Disposable;
import org.rx.core.NQuery;
import org.rx.core.RunFlag;
import org.rx.core.Tasks;
import org.rx.util.RedoTimer;
import org.rx.util.function.Action;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.rx.core.App.quietly;

@Slf4j
final class SequentialWriteQueue extends Disposable {
    @Setter
    long delaySavePeriod = 500;
    @Getter
    private final IntWaterMark waterMark = new IntWaterMark(4, 8);
    private final TreeMap<Long, Action> sortMap = new TreeMap<>();
    private final RedoTimer timer = new RedoTimer();
    private Timeout timeout;

    @Override
    protected void freeObjects() {
        List<Action> actions = NQuery.of(sortMap.values()).toList();
        for (Action action : actions) {
            quietly(action);
        }

        //避免action 又 offer 死循环
//        consume();
    }

    @SneakyThrows
    public synchronized void offer(long position, Action writeAction) {
        checkNotClosed();

        if (timeout != null) {
            timeout.cancel();
        }

        sortMap.put(position, writeAction);
        if (sortMap.size() > waterMark.getHigh()) {
            log.debug("high water mark");
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
                    log.debug("low water mark");
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
