package org.rx.io;

import io.netty.util.Timeout;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.rx.core.App;
import org.rx.util.RedoTimer;

import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.rx.core.App.require;

final class BinaryIndexFileStream {
    @RequiredArgsConstructor
    static class MetaData implements Serializable {
        private static final long serialVersionUID = -4204525178919466203L;

        private transient BinaryIndexFileStream metaStore;
        private final AtomicInteger size = new AtomicInteger();
        @Getter
        private volatile long logPosition;

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

        public void setLogPosition(long logPosition) {
            this.logPosition = logPosition;
            delaySave();
        }

        private void delaySave() {
            if (metaStore != null) {
                metaStore.delaySave();
            }
        }
    }

    private static final int DELAY_SAVE_META = 1000;
    private final KeyValueStore<?, ?> owner;
    private final FileStream.Block block;
    final MetaData meta;
    private final RedoTimer timer = new RedoTimer();
    private volatile Timeout saveTimeout;

    public BinaryIndexFileStream(KeyValueStore<?, ?> owner, FileStream.Block block) {
        this.owner = owner;
        this.block = block;
        meta = loadMetaData();
    }

    public void saveMetaData() {
        require(owner, !owner.isClosed());

        IOStream<?, ?> writer = owner.main;
        owner.locker.writeInvoke(() -> {
            meta.setLogPosition(writer.getPosition());
            writer.setPosition(block.position);
            owner.serializer.serialize(meta, writer);
            writer.setPosition(meta.getLogPosition());
        }, block.position, block.size);
    }

    @SneakyThrows
    public BinaryIndexFileStream.MetaData loadMetaData() {
        require(owner, !owner.isClosed());
        if (owner.main.getLength() == 0) {
            return new BinaryIndexFileStream.MetaData();
        }

        IOStream<?, ?> reader = owner.main;
        return owner.locker.readInvoke(() -> {
            reader.setPosition(block.position);
            try {
                return owner.serializer.deserialize(reader, true);
            } catch (Exception e) {
                if (e instanceof StreamCorruptedException) {
                    App.log("loadMetaData", e);
                    return new BinaryIndexFileStream.MetaData();
                }
                throw e;
            }
        }, block.position, block.size);
    }

    private void delaySave() {
        saveMetaData();
//        if (saveTimeout != null) {
//            saveTimeout.cancel();
//        }
//        saveTimeout = timer.setTimeout(t -> saveMetaData(), DELAY_SAVE_META);
    }
}
