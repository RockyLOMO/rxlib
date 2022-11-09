package org.rx.io;

import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.bean.IntWaterMark;
import org.rx.bean.Tuple;
import org.rx.core.Disposable;
import org.rx.core.ResetEventWait;
import org.rx.core.Tasks;
import org.rx.core.TimeoutFlag;
import org.rx.util.function.BiAction;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.rx.core.Extends.quietly;

@Slf4j
final class WriteBehindQueue<K, V> extends Disposable {
    @Getter
    final long writeDelayed;
    @Getter
    final IntWaterMark funcWaterMark;
    //sequential
    final ConcurrentSkipListMap<K, Tuple<V, BiAction<V>>> funcs = new ConcurrentSkipListMap<>();
    final ResetEventWait syncRoot = new ResetEventWait();

    WriteBehindQueue(long writeDelayed, int highFuncWaterMark) {
        this(writeDelayed, new IntWaterMark((int) Math.ceil(highFuncWaterMark / 2d), highFuncWaterMark));
    }

    WriteBehindQueue(long writeDelayed, @NonNull IntWaterMark funcWaterMark) {
        this.writeDelayed = writeDelayed;
        this.funcWaterMark = funcWaterMark;
    }

    @Override
    protected void freeObjects() {
        consume();
    }

    public void reset() {
        funcs.clear();
        syncRoot.set();
    }

    @SneakyThrows
    public void offer(@NonNull K posKey, V writeVal, BiAction<V> writeFunc) {
        if (isClosed()) {
            writeFunc.invoke(writeVal);
            return;
        }

        funcs.put(posKey, Tuple.of(writeVal, writeFunc));
        if (funcs.size() > funcWaterMark.getHigh()) {
            log.warn("high water mark threshold");
            Tasks.timer().setTimeout(this::consume, d -> d == 0 ? 1 : writeDelayed, this, TimeoutFlag.SINGLE.flags());
            syncRoot.waitOne();
            syncRoot.reset();
            log.info("below low water mark");
        }

        Tasks.setTimeout(this::consume, writeDelayed, this, TimeoutFlag.SINGLE.flags());
        log.debug("offer {} delay={}", posKey, writeDelayed);
    }

    public boolean remove(@NonNull K posKey) {
        return funcs.remove(posKey) != null;
    }

    public V peek(@NonNull K posKey) {
        Tuple<V, BiAction<V>> tuple = funcs.get(posKey);
        if (tuple == null) {
            return null;
        }
        return tuple.left;
    }

    public synchronized void consume() {
        int size = funcs.size();
        while (size > 0) {
            Map.Entry<K, Tuple<V, BiAction<V>>> entry = funcs.pollFirstEntry();
            if (funcs.size() <= funcWaterMark.getLow()) {
                log.debug("low water mark threshold");
                syncRoot.set();
            }
            if (entry == null) {
                break;
            }
            Tuple<V, BiAction<V>> tuple = entry.getValue();
            quietly(() -> tuple.right.invoke(tuple.left));
            log.debug("consume {}", entry.getKey());
            size--;
        }
    }
}
