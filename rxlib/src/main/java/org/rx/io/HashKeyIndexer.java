//package org.rx.io;
//
//import io.netty.buffer.ByteBuf;
//import lombok.EqualsAndHashCode;
//import lombok.NonNull;
//import lombok.RequiredArgsConstructor;
//import lombok.ToString;
//import lombok.extern.slf4j.Slf4j;
//import org.rx.codec.CodecUtil;
//import org.rx.core.Cache;
//import org.rx.core.Constants;
//import org.rx.core.Disposable;
//import org.rx.core.Strings;
//import org.rx.core.cache.MemoryCache;
//
//import java.io.File;
//import java.nio.channels.FileChannel;
//
//import static org.rx.core.Extends.require;
//import static org.rx.core.Extends.tryClose;
//
///**
// * only cache key position
// *
// * @param <TK>
// */
//@Slf4j
//final class HashKeyIndexer<TK> extends Disposable implements KeyIndexer<TK> {
//    @EqualsAndHashCode(callSuper = true)
//    @ToString
//    static class KeyData<TK> extends KeyEntity<TK> {
//        private long position = Constants.IO_EOF;
//        final long hashId;
//
//        public KeyData(TK key) {
//            super(key);
//            hashId = CodecUtil.hash64(Serializer.DEFAULT.serializeToBytes(key));
//        }
//    }
//
//    @RequiredArgsConstructor
//    static class Slot {
//        private final HashKeyIndexer<?> owner;
//        private final FileStream main;
//        private final CompositeLock lock;
//        private long _wroteBytes = HEADER_SIZE;
//        private IOStream writer, reader;
//
//        Slot(HashKeyIndexer<?> owner, File indexFile) {
//            this.owner = owner;
//            main = new FileStream(indexFile, FileMode.READ_WRITE, Constants.NON_BUF);
//            lock = main.getLock();
//            if (!ensureGrow()) {
//                createStream();
//            }
//            loadSize();
//        }
//
//        private void createStream() {
//            lock.writeInvoke(() -> {
//                long length = main.getLength();
//                writer = main.mmap(FileChannel.MapMode.READ_WRITE, 0, length);
//                reader = main.mmap(FileChannel.MapMode.READ_ONLY, 0, length);
//            });
//        }
//
//        private void releaseStream() {
//            lock.writeInvoke(() -> {
//                tryClose(writer);
//                tryClose(reader);
//            });
//        }
//
//        private boolean ensureGrow() {
//            return lock.writeInvoke(() -> {
//                long length = main.getLength();
//                if (length < owner.growSize
//                        || (float) getWroteBytes() / length > WALFileStream.GROW_FACTOR) {
////                    assert wroteBytes != 0;
//                    long resize = length + owner.growSize;
//                    log.debug("growSize {} {}->{}", main.getName(), length, resize);
//                    releaseStream();
//                    main.setLength(resize);
//                    createStream();
//                    return true;
//                }
//                return false;
//            });
//        }
//
//        public synchronized long getWroteBytes() {
//            return _wroteBytes;
//        }
//
//        public synchronized void incrementWroteBytes() {
//            _wroteBytes += KEY_SIZE;
//            saveSize();
//        }
//
//        public synchronized void resetWroteBytes() {
//            _wroteBytes = HEADER_SIZE;
//        }
//
//        void saveSize() {
//            ByteBuf buf = Bytes.directBuffer(HEADER_SIZE);
//            try {
//                lock.writeInvoke(() -> {
//                    writer.setPosition(0);
//                    int size = (int) ((getWroteBytes() - HEADER_SIZE) / KEY_SIZE);
//                    buf.writeInt(size);
//                    writer.write(buf);
////                    writer.flush();
//                }, 0, HEADER_SIZE);
//            } finally {
//                buf.release();
//            }
//        }
//
//        void loadSize() {
//            ByteBuf buf = Bytes.directBuffer(HEADER_SIZE);
//            try {
//                lock.readInvoke(() -> {
//                    reader.setPosition(0);
//                    if (reader.read(buf) > 0) {
//                        int size = buf.readInt();
//                        _wroteBytes = HEADER_SIZE + (long) size * KEY_SIZE;
//                    }
//                }, 0, HEADER_SIZE);
//            } finally {
//                buf.release();
//            }
//        }
//    }
//
//    static final boolean HEX_DUMP = false;
//    static final int HEADER_SIZE = 4;
//    static final int KEY_SIZE = 16;
//    //    static final int READ_PAGE_CACHE_SIZE = (int) Math.floor(1024d * 4 / KEY_SIZE) * KEY_SIZE;
//    static final int HASH_BITS = 0x7fffffff;
//
//    static int spread(int h) {
//        return (h ^ (h >>> 16)) & HASH_BITS;
//    }
//
//    private final File directory;
//    private final int growSize;
//    private final Slot[] slots;
//    private final Cache<TK, KeyData<TK>> cache = new MemoryCache<>(b -> MemoryCache.weightBuilder(b, 0.2f, 16 * 2 + 8 + 8 + 8));
//
//    public HashKeyIndexer(@NonNull File directory, long slotSize, int growSize) {
//        require(slotSize, slotSize > 0);
//        this.growSize = growSize;
//        this.directory = directory;
//
//        int slotCount = (int) Math.ceil((double) Integer.MAX_VALUE * KEY_SIZE / slotSize);
//        slots = new Slot[slotCount];
//    }
//
//    @Override
//    protected void freeObjects() {
//        for (Slot slot : slots) {
//            if (slot == null) {
//                continue;
//            }
//
//            slot.releaseStream();
//            slot.main.close();
//        }
//    }
//
//    public void clear() {
//        synchronized (slots) {
//            for (Slot slot : slots) {
//                if (slot == null) {
//                    continue;
//                }
//
//                slot.resetWroteBytes();
//            }
//        }
//    }
//
//    private Slot slot(long hashId) {
//        int hashCode = Long.hashCode(hashId);
//        int i = (slots.length - 1) & spread(hashCode);
//        synchronized (slots) {
//            Slot slot = slots[i];
//            if (slot == null) {
//                directory.mkdirs();
//                slots[i] = slot = new Slot(this, new File(directory, String.format("%s", i)));
//            }
//            return slot;
//        }
//    }
//
//    @Override
//    public KeyEntity<TK> newKey(TK key) {
//        return new KeyData<>(key);
//    }
//
//    @Override
//    public void save(@NonNull KeyEntity<TK> k) {
//        checkNotClosed();
//        KeyData<TK> key = (KeyData<TK>) k;
//        require(key, key.position >= Constants.IO_EOF);
//
//        Slot slot = slot(key.hashId);
//        slot.lock.writeInvoke(() -> {
//            slot.ensureGrow();
//
//            IOStream out = slot.writer;
//            long pos = key.position == Constants.IO_EOF ? slot.getWroteBytes() : key.position;
//            out.setPosition(pos);
//
//            ByteBuf buf = Bytes.directBuffer(KEY_SIZE);
//            try {
//                buf.writeLong(key.hashId);
//                buf.writeLong(key.logPosition);
//                out.write(buf);
////                out.flush();
//            } finally {
//                buf.release();
//            }
////            log.info("saveKey {} -> {} pos={}{}", slot.main.getName(), key, pos, dump(buf));
//
//            if (key.position == Constants.IO_EOF) {
//                slot.incrementWroteBytes();
////                log.info("wroteBytes {} -> {} pos={}/{}", slot.main.getName(), key, pos, slot.getWroteBytes());
//            }
//
//            if (key.key != null) {
////                cache.remove(key.key);
//                key.position = pos;
//                cache.put(key.key, key); //hang?
//            }
//        }, HEADER_SIZE);
//    }
//
//    @Override
//    public KeyData<TK> find(@NonNull TK k) {
//        return cache.get(k, x -> {
//            KeyData<TK> keyData = new KeyData<>(k);
//            long hashId = keyData.hashId;
//            Slot slot = slot(hashId);
//
//            return slot.lock.readInvoke(() -> {
//                IOStream in = slot.reader;
//
//                in.setPosition(HEADER_SIZE);
//                long pos = in.getPosition();
//                long endPos = slot.getWroteBytes();
//                ByteBuf buf = Bytes.directBuffer(KEY_SIZE);
//                try {
//                    while (pos < endPos && in.read(buf, KEY_SIZE) > 0) {
////                    log.debug("findKey {} -> {} pos={}{}", slot.main.getName(), hashCode, pos, dump(buf));
//                        long hash = buf.readLong();
//                        if (hash != hashId) {
//                            pos += KEY_SIZE;
//                            buf.clear();
//                            continue;
//                        }
//
//                        keyData.position = in.getPosition() - KEY_SIZE;
//                        keyData.logPosition = buf.readLong();
//                        return keyData;
//                    }
//                } finally {
//                    buf.release();
//                }
////                log.debug("findKey fail {} -> {} pos={}/{}{}", slot.main.getName(), hashCode, pos, endPos, dump(buf));
//                return null;
//            }, HEADER_SIZE);
//        });
//    }
//
//    private String dump(ByteBuf buf) {
//        if (!HEX_DUMP) {
//            return Strings.EMPTY;
//        }
//        return "\n" + Bytes.hexDump(buf);
//    }
//}
