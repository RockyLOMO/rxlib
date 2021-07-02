package org.rx.io;

import io.netty.util.Timeout;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.util.RedoTimer;
import org.rx.util.function.BiAction;
import org.rx.util.function.Func;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

final class KeyValueMetaStore {
    @RequiredArgsConstructor
    static class MetaData implements Serializable {
        private static final long serialVersionUID = -4204525178919466203L;

        private transient KeyValueMetaStore metaStore;
        @Getter
        private volatile long logLength;
        private final AtomicInteger size = new AtomicInteger();

        public void setLogLength(long logLength) {
            this.logLength = logLength;
            delaySave();
        }

        public int getSize() {
            return size.get();
        }

        public void setSize(int size) {
            this.size.set(size);
            delaySave();
        }

        public int incrementSize() {
            delaySave();
            return size.incrementAndGet();
        }

        public int decrementSize() {
            delaySave();
            return size.decrementAndGet();
        }

        private void delaySave() {
            if (metaStore != null) {
                metaStore.delaySave();
            }
        }
    }

    private static final int DELAY_SAVE_META = 15 * 1000;
    private final BiAction<MetaData> saveFunc;
    final MetaData meta;
    private final RedoTimer timer = new RedoTimer();
    private volatile Timeout saveTimeout;

    @SneakyThrows
    public KeyValueMetaStore(BiAction<MetaData> saveFunc, Func<MetaData> loadFunc) {
        this.saveFunc = saveFunc;
        meta = loadFunc.invoke();
        meta.metaStore = this;
    }

    private void delaySave() {
        if (saveTimeout != null) {
            saveTimeout.cancel();
        }
        saveTimeout = timer.setTimeout(t -> saveFunc.invoke(meta), DELAY_SAVE_META);
    }
}
