package org.rx.io;

import io.netty.buffer.ByteBuf;
import io.netty.util.NetUtil;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Disposable;

import java.io.File;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.rx.core.App.require;

@Slf4j
final class HashFileIndexer<TK> extends Disposable {
    @RequiredArgsConstructor
    @ToString
    static class KeyData<TK> {
        final TK key;
        private long position = -1;

        final int hashCode;
        long logPosition;
    }

    @RequiredArgsConstructor
    static class Slot {
        private final HashFileIndexer<?> owner;
        private final FileStream main;
        private final CompositeLock lock;
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicLong keysBytes = new AtomicLong(HEADER_SIZE);
        private IOStream<?, ?> stream;

        Slot(HashFileIndexer<?> owner, File indexFile) {
            this.owner = owner;
            main = new FileStream(indexFile, FileMode.READ_WRITE, BufferedRandomAccessFile.BufSize.TINY_DATA);
            lock = main.getLock();
            loadSize();
            ensureGrow();
        }

        void ensureGrow() {
            lock.writeInvoke(() -> {
                long length = main.getLength();
                if (length == 0
                        || (float) keysBytes.get() / length > WALFileStream.GROW_FACTOR) {
                    length += owner.bufferSize;
                    main.setLength(length);
                    log.debug("growLength {} {}", main.getName(), length);
                    if (stream != null) {
                        stream.close();
                    }
                    stream = main.mmap(FileChannel.MapMode.READ_WRITE, 0, length);
                }
            });
        }

        public int incrementSize() {
            int i = size.incrementAndGet();
            keysBytes.set(HEADER_SIZE + (long) i * KEY_SIZE);
            saveSize();
            return i;
        }

        void saveSize() {
            ByteBuf buf = Bytes.directBuffer(HEADER_SIZE, false);
            buf.writeInt(size.get());
            lock.writeInvoke(() -> {
                main.setPosition(0);
                main.write(buf);
            }, 0, HEADER_SIZE);
        }

        void loadSize() {
            ByteBuf buf = Bytes.directBuffer(HEADER_SIZE, false);
            lock.readInvoke(() -> {
                main.setPosition(0);
                main.read(buf);
            }, 0, HEADER_SIZE);
            if (buf.readableBytes() > 0) {
                size.set(buf.readInt());
                keysBytes.set(HEADER_SIZE + (long) size.get() * KEY_SIZE);
            }
        }
    }

    static final int HEADER_SIZE = 4;
    static final int KEY_SIZE = 12;
    static final int HASH_BITS = 0x7fffffff;

    static int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    private final File directory;
    private final int bufferSize;
    private final Slot[] slots;

    public HashFileIndexer(@NonNull File directory, long slotSize, int bufferSize) {
        require(slotSize, slotSize > 0);
        this.bufferSize = bufferSize;

        directory.mkdirs();
        this.directory = directory;

        int slotCount = (int) Math.ceil((double) Integer.MAX_VALUE * KEY_SIZE / slotSize);
        slots = new Slot[slotCount];
    }

    @Override
    protected void freeObjects() {
        for (Slot slot : slots) {
            if (slot == null) {
                continue;
            }

            slot.stream.close();
            slot.main.close();
        }
    }

    public void clear() {
        synchronized (slots) {
            for (int i = 0; i < slots.length; i++) {
                Slot slot = slots[i];
                if (slot == null) {
                    continue;
                }

                slot.stream.close();
                slot.main.close();
                slots[i] = null;
            }
            Files.delete(directory.getAbsolutePath());
        }
    }

    private Slot slot(int hashCode) {
        int i = (slots.length - 1) & spread(hashCode);
        synchronized (slots) {
            Slot slot = slots[i];
            if (slot == null) {
                slots[i] = slot = new Slot(this, new File(directory, String.format("%s", i)));
            }
            return slot;
        }
    }

    public void saveKey(KeyData<TK> key) {
        checkNotClosed();
        log.debug("saveKey {} {}", key.hashCode, key);

        Slot slot = slot(key.hashCode);
        ByteBuf buf = Bytes.directBuffer(KEY_SIZE, false);
        try {
            slot.lock.writeInvoke(() -> {
                slot.ensureGrow();

                IOStream<?, ?> out = slot.stream;
                out.setPosition(key.position == -1 ? slot.keysBytes.get() : key.position);

                buf.writeInt(key.hashCode);
                buf.writeLong(key.logPosition);
//                Bytes.hexDump(buf);
                out.write(buf);
                out.flush();
            });
            if (key.position == -1) {
                slot.incrementSize();
            }
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
    }

    public KeyData<TK> findKey(@NonNull TK k) {
        log.debug("findKey {}", k.hashCode());
//        return Tasks.threadMapCompute(k, x -> {
        int hashCode = k.hashCode();
        Slot slot = slot(hashCode);
        ByteBuf buf = Bytes.directBuffer(KEY_SIZE, false);
        try {
            return slot.lock.readInvoke(() -> {
                IOStream<?, ?> in = slot.stream;
//                long len = in.getLength();
//                if (len == 0) {
//                    return null;
//                }

                in.setPosition(HEADER_SIZE);
                long pos = in.getPosition();
                long endPos = slot.keysBytes.get();
                while (pos < endPos && in.read(buf, KEY_SIZE) > 0) {
                    pos += KEY_SIZE;
//                    Bytes.hexDump(buf);
                    if (buf.readInt() != hashCode) {
                        buf.clear();
                        continue;
                    }
                    long logPos = buf.readLong();
//                if (logPos == TOMB_MARK) {
//                    return null;
//                }

                    KeyData<TK> keyData = new KeyData<>(k, hashCode);
                    keyData.position = in.getPosition() - KEY_SIZE;
                    keyData.logPosition = logPos;
                    return keyData;
                }

                return null;
            });
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
//        });
    }
}
