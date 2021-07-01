package org.rx.io;

import lombok.*;
import org.rx.bean.FlagsEnum;
import org.rx.bean.NEnum;
import org.rx.util.function.Action;
import org.rx.util.function.Func;

import java.nio.channels.FileLock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@RequiredArgsConstructor(access = AccessLevel.MODULE)
public final class CompositeLock {
    @RequiredArgsConstructor
    @Getter
    enum Flags implements NEnum<Flags> {
        READ_WRITE_LOCK(1),
        FILE_LOCK(1 << 1),
        ALL(READ_WRITE_LOCK.value | FILE_LOCK.value);

        final int value;
    }

    static final FileStream.Block ALL_BLOCK = new FileStream.Block(0, Long.MAX_VALUE);

    private final FileStream owner;
    private final FlagsEnum<Flags> flags;
    private final ConcurrentHashMap<FileStream.Block, ReentrantReadWriteLock> rwLocks = new ConcurrentHashMap<>(0);

    @SneakyThrows
    private <T> T lock(FileStream.Block block, boolean shared, @NonNull Func<T> fn) {
        Lock lock = null;
        if (flags.has(Flags.READ_WRITE_LOCK)) {
            ReentrantReadWriteLock rwLock = overlaps(block.position, block.size);
            if (rwLock == null) {
                rwLock = rwLocks.computeIfAbsent(block, k -> new ReentrantReadWriteLock());
            }
            lock = shared ? rwLock.readLock() : rwLock.writeLock();
            lock.lock();
        }

        FileLock fLock = null;
        try {
            if (flags.has(Flags.FILE_LOCK)) {
                fLock = owner.getRandomAccessFile().getChannel().lock(block.position, block.size, shared);
            }

            return fn.invoke();
        } finally {
            if (fLock != null) {
                fLock.release();
            }
            if (lock != null) {
                lock.unlock();
            }
        }
    }

    private ReentrantReadWriteLock overlaps(long position, long size) {
        for (Map.Entry<FileStream.Block, ReentrantReadWriteLock> entry : rwLocks.entrySet()) {
            FileStream.Block block = entry.getKey();
            if (position + size <= block.position)
                continue;               // That is below this
            if (block.position + block.size <= position)
                continue;               // This is below that
            return entry.getValue();
        }
        return null;
    }

    public void readInvoke(Action action) {
        lock(ALL_BLOCK, true, action.toFunc());
    }

    public void readInvoke(Action action, long position, long size) {
        lock(new FileStream.Block(position, size), true, action.toFunc());
    }

    public void writeInvoke(Action action) {
        lock(ALL_BLOCK, false, action.toFunc());
    }

    public void writeInvoke(Action action, long position, long size) {
        lock(new FileStream.Block(position, size), false, action.toFunc());
    }

    public <T> T readInvoke(Func<T> action) {
        return lock(ALL_BLOCK, true, action);
    }

    public <T> T readInvoke(Func<T> action, long position, long size) {
        return lock(new FileStream.Block(position, size), true, action);
    }

    public <T> T writeInvoke(Func<T> action) {
        return lock(ALL_BLOCK, false, action);
    }

    public <T> T writeInvoke(Func<T> action, long position, long size) {
        return lock(new FileStream.Block(position, size), false, action);
    }
}
