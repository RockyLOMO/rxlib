package org.rx.core.cache;

import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.bean.Tuple;
import org.rx.codec.CodecUtil;
import org.rx.core.*;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;
import org.rx.third.guava.AbstractSequentialIterator;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.rx.core.Constants.NON_UNCHECKED;

@Slf4j
public class H2StoreCache<TK, TV> implements Cache<TK, TV>, EventPublisher<H2StoreCache<TK, TV>> {
    static final int DEFAULT_ITERATOR_SIZE = 1000;
    static final int DEFAULT_STRIPE_COUNT = 4;
    static final long DEFAULT_TOMBSTONE_EXPIRE_MILLIS = TimeUnit.SECONDS.toMillis(15);
    static final long DEFAULT_FLUSH_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);
    static final long DEFAULT_RETRY_DELAY_MILLIS = 200;
    static final long DEFAULT_L1_CACHE_MAX_SIZE = 2048L;
    static final AtomicInteger CACHE_COUNTER = new AtomicInteger();

    enum TombstoneValue {
        INSTANCE
    }

    enum PendingOpType {
        PUT,
        REMOVE,
        RENEW
    }

    static final class PendingOp {
        final Object physicalKey;
        final long seq;
        final long epoch;
        final PendingOpType type;
        final H2CacheItem<Object, Object> itemSnapshot;
        volatile int retryCount;

        PendingOp(Object physicalKey, long seq, long epoch, PendingOpType type, H2CacheItem<Object, Object> itemSnapshot) {
            this.physicalKey = physicalKey;
            this.seq = seq;
            this.epoch = epoch;
            this.type = type;
            this.itemSnapshot = itemSnapshot;
        }
    }

    static final class FlushWaiter {
        final long targetSeq;
        final CompletableFuture<Long> future = new CompletableFuture<>();

        FlushWaiter(long targetSeq) {
            this.targetSeq = targetSeq;
        }
    }

    static final class WriteResult<TV> {
        final PendingOp op;
        final TV oldValue;

        WriteResult(PendingOp op, TV oldValue) {
            this.op = op;
            this.oldValue = oldValue;
        }
    }

    static final class LoadResult {
        final long epoch;
        final H2CacheItem<Object, Object> item;

        LoadResult(long epoch, H2CacheItem<Object, Object> item) {
            this.epoch = epoch;
            this.item = item;
        }
    }

    final class StripeState implements Runnable {
        final int index;
        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        final Thread worker;

        StripeState(int cacheId, int index) {
            this.index = index;
            worker = new Thread(this, "H2StoreCache-" + cacheId + "-stripe-" + index);
            worker.setDaemon(true);
            worker.start();
        }

        void offer(Object key) {
            queue.offer(key);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    flushPendingKey(queue.take());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    log.error("stripe[{}] worker error", index, e);
                }
            }
        }
    }

    public class EntrySetView extends AbstractSet<Entry<TK, TV>> {
        final String keyPrefix;
        final int offset;
        final int iterateSize;
        final KeySetView keyView = new KeySetView();

        EntrySetView() {
            this(null, 0, Integer.MAX_VALUE);
        }

        EntrySetView(String keyPrefix) {
            this(keyPrefix, 0, DEFAULT_ITERATOR_SIZE);
        }

        EntrySetView(String keyPrefix, int offset, int size) {
            this.keyPrefix = keyPrefix;
            this.offset = Math.max(0, offset);
            this.iterateSize = size <= 0 ? 0 : size;
        }

        public class KeySetView extends AbstractSet<TK> {
            @Override
            public Iterator<TK> iterator() {
                Iterator<H2CacheItem> iterator = itemIterator();
                return new Iterator<TK>() {
                    @Override
                    public boolean hasNext() {
                        return iterator.hasNext();
                    }

                    @SuppressWarnings(NON_UNCHECKED)
                    @Override
                    public TK next() {
                        return (TK) unwrapPhysicalKey(iterator.next().getKey(), keyPrefix);
                    }

                    @Override
                    public void remove() {
                        iterator.remove();
                    }
                };
            }

            @Override
            public int size() {
                return EntrySetView.this.size();
            }

            @Override
            public boolean contains(Object o) {
                return EntrySetView.this.contains(o);
            }

            @SuppressWarnings(NON_UNCHECKED)
            @Override
            public boolean add(TK key) {
                return EntrySetView.this.putKey(key, (TV) Boolean.TRUE) == null;
            }

            @Override
            public boolean remove(Object o) {
                return EntrySetView.this.remove(o);
            }

            @Override
            public void clear() {
                EntrySetView.this.clear();
            }
        }

        @Override
        public Iterator<Map.Entry<TK, TV>> iterator() {
            Iterator<H2CacheItem> iterator = itemIterator();
            if (!iterator.hasNext()) {
                return IteratorUtils.emptyIterator();
            }
            return new Iterator<Map.Entry<TK, TV>>() {
                H2CacheItem current;

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Map.Entry<TK, TV> next() {
                    return wrapEntry(current = iterator.next());
                }

                @Override
                public void remove() {
                    iterator.remove();
                }
            };
        }

        Iterator<H2CacheItem> itemIterator() {
            if (iterateSize == 0) {
                return IteratorUtils.emptyIterator();
            }

            final int[] state = {offset, 0, iterateSize};
            int firstSize = Math.min(prefetchCount, state[2]);
            List<H2CacheItem> firstPage = findPersistedItems(newQuery().limit(state[0], firstSize));
            if (firstPage.isEmpty()) {
                return IteratorUtils.emptyIterator();
            }
            state[0] += firstPage.size();
            return new AbstractSequentialIterator<H2CacheItem>(firstPage.get(0)) {
                List<H2CacheItem> page = firstPage;
                H2CacheItem current;

                @Override
                protected H2CacheItem computeNext(H2CacheItem previous) {
                    current = previous;
                    if (--state[2] <= 0) {
                        return null;
                    }
                    while (true) {
                        if (++state[1] == page.size()) {
                            int nextSize = Math.min(prefetchCount, state[2]);
                            page = findPersistedItems(newQuery().limit(state[0], nextSize));
                            if (page.isEmpty()) {
                                return null;
                            }
                            state[0] += page.size();
                            state[1] = 0;
                        }
                        return page.get(state[1]);
                    }
                }

                @Override
                public void remove() {
                    H2StoreCache.this.removePhysicalKey(current.getKey());
                }
            };
        }

        EntityQueryLambda<H2CacheItem> newQuery() {
            EntityQueryLambda<H2CacheItem> q = new EntityQueryLambda<>(H2CacheItem.class);
            if (keyPrefix != null) {
                q.like(H2CacheItem::getRegion, buildRegionNamespace(keyPrefix) + ":%");
            }
            return q;
        }

        public Set<TK> keys() {
            return keyView;
        }

        @Override
        public int size() {
            int total = keyPrefix == null ? H2StoreCache.this.size() : countPersisted(newQuery());
            if (offset >= total) {
                return 0;
            }
            return Math.min(total - offset, iterateSize);
        }

        @Override
        public boolean contains(Object key) {
            Object lookupKey = key instanceof Map.Entry ? ((Map.Entry<?, ?>) key).getKey() : key;
            return H2StoreCache.this.containsPhysicalKey(toPhysicalKey(lookupKey));
        }

        @Override
        public boolean add(Entry<TK, TV> entry) {
            return putKey(entry.getKey(), entry.getValue()) == null;
        }

        @Override
        public boolean remove(Object key) {
            Object lookupKey = key instanceof Map.Entry ? ((Map.Entry<?, ?>) key).getKey() : key;
            return H2StoreCache.this.removePhysicalKey(toPhysicalKey(lookupKey)) != null;
        }

        @Override
        public void clear() {
            Iterator<Entry<TK, TV>> iterator = iterator();
            while (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }

        @SuppressWarnings(NON_UNCHECKED)
        Entry<TK, TV> wrapEntry(H2CacheItem item) {
            if (keyPrefix == null) {
                return item;
            }
            return new java.util.AbstractMap.SimpleEntry<>((TK) unwrapPhysicalKey(item.getKey(), keyPrefix), (TV) item.getValue());
        }

        Object toPhysicalKey(Object key) {
            return wrapPhysicalKey(requireKey(key), keyPrefix);
        }

        TV putKey(TK key, TV value) {
            requireKey(key);
            return H2StoreCache.this.putPhysicalKey(toPhysicalKey(key), key, value, null, buildRegion(key.getClass(), keyPrefix));
        }
    }

    public static final H2StoreCache<?, ?> DEFAULT;

    static {
        IOC.register(H2StoreCache.class, DEFAULT = new H2StoreCache<>());
    }

    public final Delegate<H2StoreCache<TK, TV>, Map.Entry<TK, TV>> onExpired = Delegate.create();
    final EntityDatabase db;
    @Setter
    int defaultExpireSeconds = 60 * 60 * 24 * 90;  //3 months
    @Setter
    int prefetchCount = 100;
    @Setter
    long expungePeriod = 1000 * 60;
    @Setter
    long tombstoneExpireMillis = DEFAULT_TOMBSTONE_EXPIRE_MILLIS;
    @Setter
    long flushTimeoutMillis = DEFAULT_FLUSH_TIMEOUT_MILLIS;
    @Setter
    long retryDelayMillis = DEFAULT_RETRY_DELAY_MILLIS;
    final EntrySetView setView = new EntrySetView();
    final MemoryCache<Object, H2CacheItem> l1Cache;
    final Set<Object> renewingKeys = ConcurrentHashMap.newKeySet();
    final ConcurrentHashMap<Object, PendingOp> pendingLatest = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Object, CompletableFuture<LoadResult>> loadingMap = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Object, Long> persistedSeq = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Object, ConcurrentLinkedQueue<FlushWaiter>> flushWaiters = new ConcurrentHashMap<>();
    final AtomicLong sequence = new AtomicLong();
    final AtomicLong epoch = new AtomicLong();
    final ReentrantReadWriteLock dbLock = new ReentrantReadWriteLock();
    final List<StripeState> stripes;
    final int stripeMask;

    public H2StoreCache() {
        this(EntityDatabase.DEFAULT, DEFAULT_L1_CACHE_MAX_SIZE);
    }

    public H2StoreCache(@NonNull EntityDatabase db) {
        this(db, DEFAULT_L1_CACHE_MAX_SIZE);
    }

    public H2StoreCache(@NonNull EntityDatabase db, long l1CacheMaxSize) {
        if (l1CacheMaxSize <= 0) {
            throw new IllegalArgumentException("l1CacheMaxSize must be > 0");
        }
        db.createMapping(H2CacheItem.class);
        this.db = db;
        l1Cache = new MemoryCache<>(b -> b.maximumSize(l1CacheMaxSize));

        int stripeCount = roundUpToPowerOfTwo(Math.max(DEFAULT_STRIPE_COUNT, Math.min(8, Constants.CPU_THREADS)));
        stripes = new ArrayList<>(stripeCount);
        stripeMask = stripeCount - 1;
        int cacheId = CACHE_COUNTER.incrementAndGet();
        for (int i = 0; i < stripeCount; i++) {
            stripes.add(new StripeState(cacheId, i));
        }

        Tasks.schedulePeriod(this::expungeStale, expungePeriod);
    }

    // region core api
    void expungeStale() {
        while (true) {
            List<H2CacheItem> stales = findPersistedItems(new EntityQueryLambda<>(H2CacheItem.class)
                    .le(H2CacheItem::getExpiration, System.currentTimeMillis())
                    .limit(prefetchCount));
            if (stales.isEmpty()) {
                break;
            }
            for (H2CacheItem stale : stales) {
                scheduleExpiredRemove(stale.getKey(), stale);
            }
            if (stales.size() < prefetchCount) {
                break;
            }
        }
    }

    @Override
    public int size() {
        return countPersisted(new EntityQueryLambda<>(H2CacheItem.class));
    }

    @Override
    public boolean containsKey(Object key) {
        return containsPhysicalKey(requireKey(key));
    }

    boolean containsPhysicalKey(Object key) {
        H2CacheItem<TK, TV> item = resolveVisibleItem(key, false);
        return item != null && !item.isTombstone();
    }

    @Override
    public boolean containsValue(Object value) {
        dbLock.readLock().lock();
        try {
            return db.exists(new EntityQueryLambda<>(H2CacheItem.class)
                    .eq(H2CacheItem::getValIdx, CodecUtil.hash64(value)));
        } finally {
            dbLock.readLock().unlock();
        }
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV get(Object key) {
        H2CacheItem<TK, TV> item = resolveVisibleItem(requireKey(key), true);
        return item == null || item.isTombstone() ? null : item.getValue();
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV put(TK key, TV value, CachePolicy policy) {
        requireKey(key);
        return putPhysicalKey(key, key, value, policy, buildRegion(key.getClass(), null));
    }

    public TV syncPut(TK key, TV value, CachePolicy policy) {
        requireKey(key);
        WriteResult<TV> result = submitPut(key, key, value, policy, buildRegion(key.getClass(), null), true);
        awaitFlush(result.op.physicalKey, result.op.seq);
        return result.oldValue;
    }

    public void fastPut(TK key, TV value) {
        fastPut(key, value, null);
    }

    public void fastPut(TK key, TV value, CachePolicy policy) {
        requireKey(key);
        submitPut(key, key, value, policy, buildRegion(key.getClass(), null), false);
    }

    public void fastPut(String keyPrefix, TK key, TV value) {
        fastPut(keyPrefix, key, value, null);
    }

    public void fastPut(String keyPrefix, TK key, TV value, CachePolicy policy) {
        requireKey(key);
        Object physicalKey = wrapPhysicalKey(key, keyPrefix);
        submitPut(physicalKey, key, value, policy, buildRegion(key.getClass(), keyPrefix), false);
    }

    @SuppressWarnings(NON_UNCHECKED)
    TV putPhysicalKey(Object physicalKey, Object logicalKey, TV value, CachePolicy policy, String region) {
        return submitPut(physicalKey, logicalKey, value, policy, region, true).oldValue;
    }

    void fastPutPhysicalKey(Object physicalKey, TV value, CachePolicy policy, String region) {
        submitPut(physicalKey, physicalKey, value, policy, region, false);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV remove(Object key) {
        return removePhysicalKey(requireKey(key));
    }

    public TV syncRemove(Object key) {
        requireKey(key);
        WriteResult<TV> result = submitRemove(key, true);
        if (result.op != null) {
            awaitFlush(result.op.physicalKey, result.op.seq);
        }
        return result.oldValue;
    }

    public void fastRemove(Object key) {
        requireKey(key);
        submitRemove(key, false);
    }

    @SuppressWarnings(NON_UNCHECKED)
    TV removePhysicalKey(Object key) {
        return submitRemove(key, true).oldValue;
    }

    @Override
    public void clear() {
        dbLock.writeLock().lock();
        try {
            epoch.incrementAndGet();
            pendingLatest.clear();
            loadingMap.clear();
            persistedSeq.clear();
            failAllWaiters(new IllegalStateException("cache cleared"));
            renewingKeys.clear();
            l1Cache.clear();
            db.truncateMapping(H2CacheItem.class);
        } finally {
            dbLock.writeLock().unlock();
        }
    }

    public void flush() {
        Map<Object, Long> snapshot = new LinkedHashMap<>();
        long nowEpoch = epoch.get();
        for (Map.Entry<Object, PendingOp> entry : pendingLatest.entrySet()) {
            PendingOp op = entry.getValue();
            if (op != null && op.epoch == nowEpoch) {
                snapshot.put(entry.getKey(), op.seq);
            }
        }
        if (snapshot.isEmpty()) {
            return;
        }

        List<CompletableFuture<Long>> futures = new ArrayList<>(snapshot.size());
        for (Map.Entry<Object, Long> entry : snapshot.entrySet()) {
            futures.add(registerWaiter(entry.getKey(), entry.getValue()));
            enqueueKey(entry.getKey());
        }
        awaitAll(futures);
    }

    public void flush(Object key) {
        requireKey(key);
        PendingOp op = pendingLatest.get(key);
        if (op == null) {
            return;
        }
        awaitFuture(registerWaiter(key, op.seq), key, op.seq);
    }

    public long pendingWriteCount() {
        return pendingLatest.size();
    }

    @Override
    public Set<Entry<TK, TV>> entrySet() {
        return setView;
    }

    public EntrySetView entrySet(int offset, int size) {
        return new EntrySetView(null, offset, size);
    }

    public EntrySetView entrySet(String keyPrefix) {
        return entrySet(keyPrefix, 0, DEFAULT_ITERATOR_SIZE);
    }

    public EntrySetView entrySet(String keyPrefix, int offset, int size) {
        if (keyPrefix == null && offset == 0 && size == Integer.MAX_VALUE) {
            return setView;
        }
        return new EntrySetView(keyPrefix, offset, size);
    }

    @Override
    public Set<TK> asSet() {
        return setView.keys();
    }

    public Set<TK> asSet(String keyPrefix) {
        return entrySet(keyPrefix, 0, Integer.MAX_VALUE).keys();
    }

    public Set<TK> asSet(String keyPrefix, int offset, int size) {
        return entrySet(keyPrefix, offset, size).keys();
    }

    public Iterator<Entry<TK, TV>> iterator(int offset, int size) {
        return entrySet(offset, size).iterator();
    }

    public Iterator<Entry<TK, TV>> iterator(String keyPrefix, int offset, int size) {
        return entrySet(keyPrefix, offset, size).iterator();
    }

    // region helpers
    WriteResult<TV> submitPut(Object physicalKey, Object logicalKey, TV value, CachePolicy policy, String region, boolean captureOldValue) {
        CachePolicy effectivePolicy = policy != null ? policy : CachePolicy.absolute(defaultExpireSeconds);
        TV oldValue = captureOldValue ? visibleValue(physicalKey) : null;
        long opEpoch = epoch.get();
        long seq = nextSeq();

        H2CacheItem<Object, TV> item = new H2CacheItem<>(physicalKey, value, effectivePolicy);
        item.setRegion(region != null ? region : buildRegion(logicalKey != null ? logicalKey.getClass() : Object.class, null));
        item.setVersion(seq);

        PendingOp op = new PendingOp(physicalKey, seq, opEpoch, PendingOpType.PUT, castItem(item));
        pendingLatest.put(physicalKey, op);
        l1Cache.put(physicalKey, item, item);
        enqueueKey(physicalKey);
        return new WriteResult<>(op, oldValue);
    }

    WriteResult<TV> submitRemove(Object physicalKey, boolean captureOldValue) {
        if (physicalKey == null) {
            return new WriteResult<>(null, null);
        }

        TV oldValue = captureOldValue ? visibleValue(physicalKey) : null;
        long opEpoch = epoch.get();
        long seq = nextSeq();
        H2CacheItem<Object, Object> tombstone = newTombstone(physicalKey, seq);
        PendingOp op = new PendingOp(physicalKey, seq, opEpoch, PendingOpType.REMOVE, tombstone);

        pendingLatest.put(physicalKey, op);
        l1Cache.put(physicalKey, tombstone, tombstone);
        renewingKeys.remove(physicalKey);
        enqueueKey(physicalKey);
        return new WriteResult<>(op, oldValue);
    }

    void scheduleExpiredRemove(Object key, H2CacheItem<?, ?> item) {
        if (key == null || item == null) {
            return;
        }

        long observedVersion = item.getVersion();
        long opEpoch = epoch.get();
        long seq = nextSeq();
        H2CacheItem<Object, Object> tombstone = newTombstone(key, seq);
        PendingOp installed = installDerivedOp(new PendingOp(key, seq, opEpoch, PendingOpType.REMOVE, tombstone), observedVersion);
        if (installed != null) {
            l1Cache.put(key, tombstone, tombstone);
            renewingKeys.remove(key);
            enqueueKey(key);
            raiseEvent(onExpired, castEntry(item));
        }
    }

    void scheduleRenew(Object key, H2CacheItem<TK, TV> item) {
        if (item == null || item.isTombstone() || !item.isSliding()) {
            return;
        }
        if (!renewingKeys.add(key)) {
            return;
        }
        try {
            long observedVersion = item.getVersion();
            if (!item.slidingRenew()) {
                return;
            }

            long opEpoch = epoch.get();
            long seq = nextSeq();
            PendingOp installed = installDerivedOp(new PendingOp(key, seq, opEpoch, PendingOpType.RENEW, castItem(item)), observedVersion);
            if (installed != null) {
                item.setVersion(seq);
                l1Cache.put(key, item, item);
                enqueueKey(key);
            }
        } finally {
            renewingKeys.remove(key);
        }
    }

    PendingOp installDerivedOp(PendingOp op, long baseVersion) {
        final PendingOp[] installed = {null};
        pendingLatest.compute(op.physicalKey, (k, existing) -> {
            if (existing != null && existing.epoch == op.epoch && existing.seq > baseVersion) {
                return existing;
            }
            installed[0] = op;
            return op;
        });
        return installed[0];
    }

    void enqueueKey(Object key) {
        stripes.get(stripeIndex(key)).offer(key);
    }

    void flushPendingKey(Object key) {
        PendingOp op = pendingLatest.get(key);
        if (op == null) {
            return;
        }
        if (op.epoch != epoch.get()) {
            pendingLatest.remove(key, op);
            return;
        }

        try {
            PendingOp latest = pendingLatest.get(key);
            if (latest != null && latest.epoch == op.epoch && latest.seq > op.seq) {
                return;
            }
            if (op.type != PendingOpType.REMOVE && op.itemSnapshot.isExpired()) {
                scheduleExpiredRemove(key, op.itemSnapshot);
                return;
            }

            dbLock.readLock().lock();
            try {
                if (op.epoch != epoch.get()) {
                    return;
                }
                latest = pendingLatest.get(key);
                if (latest != null && latest.epoch == op.epoch && latest.seq > op.seq) {
                    return;
                }
                switch (op.type) {
                    case PUT:
                    case RENEW:
                        op.itemSnapshot.setTombstone(false);
                        db.save(op.itemSnapshot);
                        break;
                    case REMOVE:
                        deletePersistedIfMatched(key, op.seq);
                        break;
                    default:
                        break;
                }
            } finally {
                dbLock.readLock().unlock();
            }
        } catch (Throwable e) {
            op.retryCount++;
            scheduleRetry(key, op, e);
            return;
        }

        persistedSeq.merge(key, op.seq, Math::max);
        completeWaiters(key, op.seq);

        PendingOp current = pendingLatest.get(key);
        if (current != null && current.seq == op.seq && current.epoch == op.epoch) {
            pendingLatest.remove(key, current);
        }
    }

    void scheduleRetry(Object key, PendingOp op, Throwable error) {
        if (pendingLatest.get(key) != op) {
            return;
        }
        log.warn("flush pending key={} seq={} retry={} error={}", key, op.seq, op.retryCount, error.toString());
        long delay = retryDelayMillis * Math.min(10, Math.max(1, op.retryCount));
        Tasks.setTimeout(() -> {
            if (pendingLatest.get(key) == op) {
                enqueueKey(key);
            }
        }, delay);
    }

    boolean deletePersistedIfMatched(Object key, long maxVersion) {
        H2CacheItem<TK, TV> item = findPersisted(key);
        if (item == null) {
            return false;
        }
        if (item.getVersion() > maxVersion) {
            return false;
        }
        return db.deleteById(H2CacheItem.class, item.getId());
    }

    TV visibleValue(Object key) {
        H2CacheItem<TK, TV> item = resolveVisibleItem(key, false);
        return item == null || item.isTombstone() ? null : item.getValue();
    }

    H2CacheItem<TK, TV> resolveVisibleItem(Object key, boolean renew) {
        H2CacheItem<TK, TV> item = resolveVisibleFromMemory(key);
        if (item == null) {
            item = loadVisibleFromDb(key);
        }
        if (item != null && renew && !item.isTombstone()) {
            scheduleRenew(key, item);
        }
        return item;
    }

    H2CacheItem<TK, TV> resolveVisibleFromMemory(Object key) {
        H2CacheItem<TK, TV> item = normalizeL1Item(key, getL1(key));
        if (item != null) {
            return item;
        }
        return pendingVisibleItem(key);
    }

    H2CacheItem<TK, TV> normalizeL1Item(Object key, H2CacheItem<TK, TV> item) {
        if (item == null) {
            return null;
        }
        if (item.isTombstone()) {
            if (item.isExpired()) {
                l1Cache.remove(key, item);
                return null;
            }
            return item;
        }
        if (item.isExpired()) {
            l1Cache.remove(key, item);
            scheduleExpiredRemove(key, item);
            return pendingVisibleItem(key);
        }
        return item;
    }

    H2CacheItem<TK, TV> pendingVisibleItem(Object key) {
        PendingOp op = pendingLatest.get(key);
        if (op == null) {
            return null;
        }
        if (op.epoch != epoch.get()) {
            pendingLatest.remove(key, op);
            return null;
        }
        if (op.type == PendingOpType.REMOVE) {
            return castTypedItem(op.itemSnapshot);
        }
        H2CacheItem<TK, TV> item = castTypedItem(op.itemSnapshot);
        if (!item.isExpired()) {
            return item;
        }
        scheduleExpiredRemove(key, item);
        PendingOp latest = pendingLatest.get(key);
        return latest != null && latest.type == PendingOpType.REMOVE ? castTypedItem(latest.itemSnapshot) : null;
    }

    H2CacheItem<TK, TV> loadVisibleFromDb(Object key) {
        LoadResult load = awaitLoad(key);
        if (load == null || load.item == null) {
            return null;
        }
        long readEpoch = load.epoch;
        if (readEpoch != epoch.get()) {
            return null;
        }
        H2CacheItem<TK, TV> item = castTypedItem(load.item);
        if (item == null) {
            return null;
        }
        if (item.isExpired()) {
            scheduleExpiredRemove(key, item);
            PendingOp latest = pendingLatest.get(key);
            return latest != null && latest.type == PendingOpType.REMOVE ? castTypedItem(latest.itemSnapshot) : null;
        }
        if (!canPublishLoadedItem(key, readEpoch, item.getVersion())) {
            H2CacheItem<TK, TV> current = resolveVisibleFromMemory(key);
            return current != null ? current : null;
        }
        l1Cache.put(key, item, item);
        return item;
    }

    LoadResult awaitLoad(Object key) {
        CompletableFuture<LoadResult> loader = new CompletableFuture<>();
        CompletableFuture<LoadResult> existing = loadingMap.putIfAbsent(key, loader);
        if (existing == null) {
            try {
                long readEpoch = epoch.get();
                H2CacheItem<TK, TV> item;
                dbLock.readLock().lock();
                try {
                    if (readEpoch != epoch.get()) {
                        item = null;
                    } else {
                        item = findPersisted(key);
                    }
                } finally {
                    dbLock.readLock().unlock();
                }
                LoadResult result = new LoadResult(readEpoch, item == null ? null : castItem(item));
                loader.complete(result);
                return result;
            } catch (Throwable e) {
                loader.completeExceptionally(e);
                throw new IllegalStateException("load failed key=" + key, e);
            } finally {
                loadingMap.remove(key, loader);
            }
        }

        try {
            return existing.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("load interrupted key=" + key, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("load failed key=" + key, e.getCause() != null ? e.getCause() : e);
        }
    }

    boolean canPublishLoadedItem(Object key, long readEpoch, long loadedVersion) {
        if (readEpoch != epoch.get()) {
            return false;
        }
        PendingOp pending = pendingLatest.get(key);
        if (pending != null && pending.epoch == readEpoch) {
            return false;
        }
        H2CacheItem<TK, TV> l1 = getL1(key);
        if (l1 == null) {
            return true;
        }
        if (l1.isTombstone()) {
            return false;
        }
        return l1.getVersion() <= loadedVersion;
    }

    CompletableFuture<Long> registerWaiter(Object key, long targetSeq) {
        long persisted = persistedSeq.getOrDefault(key, 0L);
        if (persisted >= targetSeq) {
            return CompletableFuture.completedFuture(persisted);
        }

        FlushWaiter waiter = new FlushWaiter(targetSeq);
        ConcurrentLinkedQueue<FlushWaiter> waiters = flushWaiters.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        waiters.offer(waiter);

        long latestPersisted = persistedSeq.getOrDefault(key, 0L);
        if (latestPersisted >= targetSeq) {
            completeWaiters(key, latestPersisted);
        }
        return waiter.future;
    }

    void completeWaiters(Object key, long seq) {
        ConcurrentLinkedQueue<FlushWaiter> waiters = flushWaiters.get(key);
        if (waiters == null) {
            return;
        }
        for (FlushWaiter waiter : waiters) {
            if (waiter.targetSeq <= seq && waiters.remove(waiter)) {
                waiter.future.complete(seq);
            }
        }
        if (waiters.isEmpty()) {
            flushWaiters.remove(key, waiters);
        }
    }

    void failAllWaiters(Throwable error) {
        for (ConcurrentLinkedQueue<FlushWaiter> waiters : flushWaiters.values()) {
            for (FlushWaiter waiter : waiters) {
                waiter.future.completeExceptionally(error);
            }
            waiters.clear();
        }
        flushWaiters.clear();
    }

    void awaitFlush(Object key, long seq) {
        awaitFuture(registerWaiter(key, seq), key, seq);
    }

    void awaitAll(List<CompletableFuture<Long>> futures) {
        long timeout = Math.max(1L, flushTimeoutMillis);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        for (CompletableFuture<Long> future : futures) {
            long remain = deadline - System.nanoTime();
            if (remain <= 0) {
                throw new IllegalStateException("flush timeout");
            }
            try {
                future.get(remain, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("flush interrupted", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("flush failed", e);
            }
        }
    }

    void awaitFuture(CompletableFuture<Long> future, Object key, long seq) {
        enqueueKey(key);
        try {
            future.get(Math.max(1L, flushTimeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("flush interrupted key=" + key + " seq=" + seq, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("flush failed key=" + key + " seq=" + seq, e);
        }
    }

    List<H2CacheItem> findPersistedItems(EntityQueryLambda<H2CacheItem> query) {
        dbLock.readLock().lock();
        try {
            return db.findBy(query);
        } finally {
            dbLock.readLock().unlock();
        }
    }

    int countPersisted(EntityQueryLambda<H2CacheItem> query) {
        dbLock.readLock().lock();
        try {
            return (int) db.count(query);
        } finally {
            dbLock.readLock().unlock();
        }
    }

    long nextSeq() {
        return sequence.incrementAndGet();
    }

    int stripeIndex(Object key) {
        return spread(key == null ? 0 : key.hashCode()) & stripeMask;
    }

    int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    int roundUpToPowerOfTwo(int value) {
        int cap = 1;
        while (cap < value) {
            cap <<= 1;
        }
        return cap;
    }

    H2CacheItem<Object, Object> newTombstone(Object key, long seq) {
        long seconds = Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(Math.max(1000L, tombstoneExpireMillis)));
        H2CacheItem<Object, Object> tombstone = new H2CacheItem<>(key, TombstoneValue.INSTANCE, CachePolicy.absolute((int) seconds));
        tombstone.setTombstone(true);
        tombstone.setVersion(seq);
        return tombstone;
    }

    @SuppressWarnings(NON_UNCHECKED)
    H2CacheItem<TK, TV> getL1(Object key) {
        return (H2CacheItem<TK, TV>) l1Cache.get(key);
    }

    @SuppressWarnings(NON_UNCHECKED)
    H2CacheItem<TK, TV> findPersisted(Object key) {
        H2CacheItem<TK, TV> item = db.findById(H2CacheItem.class, CodecUtil.hash64(key));
        return item != null && Objects.equals(key, item.getKey()) ? item : null;
    }

    @SuppressWarnings(NON_UNCHECKED)
    H2CacheItem<Object, Object> castItem(H2CacheItem<?, ?> item) {
        return (H2CacheItem<Object, Object>) item;
    }

    @SuppressWarnings(NON_UNCHECKED)
    H2CacheItem<TK, TV> castTypedItem(H2CacheItem<?, ?> item) {
        return (H2CacheItem<TK, TV>) item;
    }

    @SuppressWarnings(NON_UNCHECKED)
    Map.Entry<TK, TV> castEntry(H2CacheItem<?, ?> item) {
        return (Map.Entry<TK, TV>) item;
    }

    static Object wrapPhysicalKey(Object key, String keyPrefix) {
        requireKey(key);
        if (keyPrefix == null) {
            return key;
        }
        return Tuple.of(keyPrefix, key);
    }

    static <T> T requireKey(T key) {
        return Objects.requireNonNull(key, "key");
    }

    static Object unwrapPhysicalKey(Object key, String keyPrefix) {
        if (keyPrefix == null || !(key instanceof Tuple)) {
            return key;
        }
        return ((Tuple<?, ?>) key).right;
    }

    static String buildRegion(Class<?> keyType, String keyPrefix) {
        String region = keyType == null ? Object.class.getSimpleName() : keyType.getSimpleName();
        if (keyPrefix == null) {
            return region;
        }
        return buildRegionNamespace(keyPrefix) + ":" + region;
    }

    static String buildRegionNamespace(String keyPrefix) {
        return "KP@" + Long.toHexString(CodecUtil.hash64(keyPrefix));
    }
}
