//package org.rx.io;
//
//import lombok.Getter;
//import lombok.NonNull;
//import lombok.SneakyThrows;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.bean.IntWaterMark;
//import org.rx.bean.Tuple;
//import org.rx.core.Disposable;
//import org.rx.core.ResetEventWait;
//import org.rx.core.Tasks;
//import org.rx.core.TimeoutFlag;
//import org.rx.util.function.BiAction;
//
//import java.util.Map;
//import java.util.concurrent.ConcurrentSkipListMap;
//
//import static org.rx.core.Extends.quietly;
//
//@Slf4j
//final class WriteBehindQueue<K, V> extends Disposable {
//    @Getter
//    final long writeDelayed;
//    @Getter
//    final IntWaterMark waterMark;
//    //sequential
//    final ConcurrentSkipListMap<K, Tuple<V, BiAction<V>>> actions = new ConcurrentSkipListMap<>();
//    final ResetEventWait syncRoot = new ResetEventWait();
//
//    WriteBehindQueue(long writeDelayed, int highWaterMark) {
//        this(writeDelayed, new IntWaterMark((int) Math.ceil(highWaterMark / 2d), highWaterMark));
//    }
//
//    WriteBehindQueue(long writeDelayed, @NonNull IntWaterMark waterMark) {
//        this.writeDelayed = writeDelayed;
//        this.waterMark = waterMark;
//    }
//
//    @Override
//    protected void freeObjects() {
//        flush();
//    }
//
//    public void clear() {
//        actions.clear();
//        syncRoot.set();
//    }
//
//    @SneakyThrows
//    public void offer(@NonNull K posKey, V writeVal, BiAction<V> writeAction) {
//        if (isClosed()) {
//            writeAction.invoke(writeVal);
//            return;
//        }
//
//        actions.put(posKey, Tuple.of(writeVal, writeAction));
//        if (actions.size() > waterMark.getHigh()) {
//            log.warn("high water mark threshold");
//            Tasks.timer().setTimeout(this::flush, d -> d == 0 ? 1 : writeDelayed, this, TimeoutFlag.SINGLE.flags());
//            syncRoot.waitOne();
//            syncRoot.reset();
//            log.info("below low water mark");
//        }
//
//        Tasks.setTimeout(this::flush, writeDelayed, this, TimeoutFlag.SINGLE.flags());
//        log.debug("offer {} delay={}", posKey, writeDelayed);
//    }
//
//    public boolean remove(@NonNull K posKey) {
//        return actions.remove(posKey) != null;
//    }
//
//    public V peek(@NonNull K posKey) {
//        Tuple<V, BiAction<V>> tuple = actions.get(posKey);
//        if (tuple == null) {
//            return null;
//        }
//        return tuple.left;
//    }
//
//    public synchronized void flush() {
//        int size = actions.size();
//        while (size > 0) {
//            Map.Entry<K, Tuple<V, BiAction<V>>> entry = actions.pollFirstEntry();
//            if (actions.size() <= waterMark.getLow()) {
//                log.debug("low water mark threshold");
//                syncRoot.set();
//            }
//            if (entry == null) {
//                break;
//            }
//            Tuple<V, BiAction<V>> tuple = entry.getValue();
//            quietly(() -> tuple.right.invoke(tuple.left));
//            log.debug("flush {}", entry.getKey());
//            size--;
//        }
//    }
//}
