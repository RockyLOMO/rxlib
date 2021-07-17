package org.rx.io;

import io.netty.util.Timeout;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.IntWaterMark;
import org.rx.core.*;
import org.rx.util.RedoTimer;
import org.rx.util.function.Action;

import java.util.*;

import static org.rx.core.App.quietly;

@Slf4j
final class WriteBehindQueue extends Disposable {
    @Setter
    private long delaySavePeriod = 500;
    @Getter
    private final IntWaterMark waterMark;
    //sequential
    private final NavigableMap<Long, Action> sortMap = Collections.synchronizedNavigableMap(new TreeMap<>());
    private final RedoTimer timer = new RedoTimer();
    private volatile Timeout timeout;
    private final ManualResetEvent event = new ManualResetEvent();
    private volatile boolean stop;  //避免consume时又offer 死循环

    WriteBehindQueue(int highWaterMark) {
        this(new IntWaterMark((int) Math.ceil(highWaterMark / 2d), highWaterMark));
    }

    WriteBehindQueue(@NonNull IntWaterMark waterMark) {
        this.waterMark = waterMark;
    }

    @Override
    protected void freeObjects() {
        stop = true;
        consume();
    }

    @SneakyThrows
    public void offer(long position, Action writeAction) {
        checkNotClosed();
        if (stop) {
            writeAction.invoke();
            return;
        }

        sortMap.put(position, writeAction);

        if (sortMap.size() > waterMark.getHigh()) {
            log.warn("high water mark threshold");
            if (timeout == null) {
                timeout = timer.setTimeout(this::consume, 1);
            }
            event.waitOne();
            event.reset();
            log.info("below low water mark");
        }

        if (timeout != null) {
            timeout.cancel();
        }
        timeout = timer.setTimeout(this::consume, delaySavePeriod);
    }

    public void consume() {
        consume(timeout);
    }

    private void consume(Timeout t) {
        int size = sortMap.size();
        while (size > 0) {
            Map.Entry<Long, Action> entry = sortMap.pollFirstEntry();
            if (sortMap.size() <= waterMark.getLow()) {
                log.debug("low water mark threshold");
                event.set();
            }
            if (entry == null) {
                break;
            }
            quietly(entry.getValue());
            size--;
        }
    }
}
