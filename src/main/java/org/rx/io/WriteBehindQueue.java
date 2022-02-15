package org.rx.io;

import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.IntWaterMark;
import org.rx.bean.Tuple;
import org.rx.core.*;
import org.rx.util.function.BiAction;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.rx.core.Extends.quietly;

@Slf4j
final class WriteBehindQueue<K, V> extends Disposable {
    @Getter
    private final long writeDelayed;
    @Getter
    private final IntWaterMark waterMark;
    //sequential
    private final ConcurrentSkipListMap<K, Tuple<V, BiAction<V>>> sortMap = new ConcurrentSkipListMap<>();
    private final ManualResetEvent syncRoot = new ManualResetEvent();
    private volatile boolean stop;  //避免consume时又offer 死循环

    WriteBehindQueue(long writeDelayed, int highWaterMark) {
        this(writeDelayed, new IntWaterMark((int) Math.ceil(highWaterMark / 2d), highWaterMark));
    }

    WriteBehindQueue(long writeDelayed, @NonNull IntWaterMark waterMark) {
        this.writeDelayed = writeDelayed;
        this.waterMark = waterMark;
    }

    @Override
    protected void freeObjects() {
        stop = true;
        consume();
    }

    public void reset() {
        sortMap.clear();
        syncRoot.set();
    }

    public V peek(@NonNull K posKey) {
        return sortMap.getOrDefault(posKey, new Tuple<>()).left;
    }

    @SneakyThrows
    public void offer(@NonNull K posKey, V writeVal, BiAction<V> writeAction) {
        checkNotClosed();
        if (stop) {
            writeAction.invoke(writeVal);
            return;
        }

        sortMap.put(posKey, Tuple.of(writeVal, writeAction));

        if (sortMap.size() > waterMark.getHigh()) {
            log.warn("high water mark threshold");
            Tasks.timer().setTimeout(() -> {
                consume();
                return false;
            }, d -> d == 0 ? 1 : writeDelayed, this, TimeoutFlag.SINGLE);
            syncRoot.waitOne();
            syncRoot.reset();
            log.info("below low water mark");
        }

        Tasks.setTimeout(this::consume, writeDelayed, this, TimeoutFlag.SINGLE);
        log.debug("offer {} {} delay={}", posKey, writeVal, writeDelayed);
    }

    public boolean replace(@NonNull K posKey, V writeVal) {
        if (writeVal == null) {
            return sortMap.remove(posKey) != null;
        }

        Tuple<V, BiAction<V>> tuple = sortMap.get(posKey);
        if (tuple == null) {
            return false;
        }
        tuple.left = writeVal;
        return true;
    }

    public void consume() {
        int size = sortMap.size();
        while (size > 0) {
            Map.Entry<K, Tuple<V, BiAction<V>>> entry = sortMap.pollFirstEntry();
            if (sortMap.size() <= waterMark.getLow()) {
                log.debug("low water mark threshold");
                syncRoot.set();
            }
            if (entry == null) {
                break;
            }
            Tuple<V, BiAction<V>> tuple = entry.getValue();
            quietly(() -> tuple.right.invoke(tuple.left));
            log.debug("consume {} {}", entry.getKey(), tuple.left);
            size--;
        }
    }
}
