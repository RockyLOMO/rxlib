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
public class H2StoreCache<TK, TV> implements Cache<TK, TV>, EventPublisher<H2StoreCache<TK, TV>>, AutoCloseable {
    static final int DEFAULT_ITERATOR_SIZE = 1000;
    static final int DEFAULT_STRIPE_COUNT = 2;
    static final long DEFAULT_TOMBSTONE_EXPIRE_MILLIS = TimeUnit.SECONDS.toMillis(15);
    static final long DEFAULT_EXPUNGE_PERIOD_MILLIS = TimeUnit.MINUTES.toMillis(3);
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

    enum DeleteResult {
        COMMITTED,
        SKIPPED_NEWER
    }

    static final class PendingOp {
        final Object physicalKey;
        final long seq;
        final long epoch;
        final PendingOpType type;
        final H2CacheItem<Object, Object> itemSnapshot;
        Map.Entry<Object, Object> expiredEventEntry;
        volatile int retryCount;
        volatile boolean rescheduleAfterFlush;

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

    static final class ExpungeCursor {
        final long expiration;
        final long id;

        ExpungeCursor(long expiration, long id) {
            this.expiration = expiration;
            this.id = id;
        }
    }

    final class StripeState implements Runnable {
        final int index;
        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        final Set<Object> queuedKeys = ConcurrentHashMap.newKeySet();
        final Thread worker;
        volatile boolean stopped;

        StripeState(int cacheId, int index) {
            this.index = index;
            worker = new Thread(this, "H2StoreCache-" + cacheId + "-stripe-" + index);
            worker.setDaemon(true);
            worker.start();
        }

        void offer(Object key) {
            if (stopped || !queuedKeys.add(key)) {
                return;
            }
            boolean offered = false;
            try {
                queue.offer(key);
                offered = true;
            } finally {
                if (!offered) {
                    queuedKeys.remove(key);
                }
            }
        }

        void shutdown() {
            stopped = true;
            queue.clear();
            queuedKeys.clear();
            worker.interrupt();
        }

        @Override
        public void run() {
            while (!stopped) {
                Object key = null;
                PendingOp op = null;
                try {
                    key = queue.take();
                    processingKeys.add(key);
                    op = flushPendingKey(key);
                } catch (InterruptedException e) {
                    if (stopped) {
                        return;
                    }
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    log.error("stripe[{}] worker error", index, e);
                } finally {
                    if (key != null) {
                        processingKeys.remove(key);
                        queuedKeys.remove(key);
                        if (shouldRequeue(key, op)) {
                            offer(key);
                        }
                    }
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
                return o != null && H2StoreCache.this.containsPhysicalKey(toPhysicalKey(o));
            }

            @SuppressWarnings(NON_UNCHECKED)
            @Override
            public boolean add(TK key) {
                return EntrySetView.this.putKey(key, (TV) Boolean.TRUE) == null;
            }

            @Override
            public boolean remove(Object o) {
                return o != null && H2StoreCache.this.removePhysicalKey(toPhysicalKey(o)) != null;
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
            List<H2CacheItem> items = liveItems();
            if (items.isEmpty()) {
                return IteratorUtils.emptyIterator();
            }
            return new AbstractSequentialIterator<H2CacheItem>(items.get(0)) {
                H2CacheItem current;
                int index;

                @Override
                protected H2CacheItem computeNext(H2CacheItem previous) {
                    current = previous;
                    if (++index >= items.size()) {
                        return null;
                    }
                    return items.get(index);
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
            q.orderBy(H2CacheItem::getId);
            return q;
        }

        List<H2CacheItem> fetchPageAfterId(Long afterId, int limit) {
            if (limit <= 0) {
                return Collections.emptyList();
            }
            EntityQueryLambda<H2CacheItem> q = newQuery();
            if (afterId != null) {
                q.gt(H2CacheItem::getId, afterId);
            }
            q.limit(limit);
            return findPersistedItems(q);
        }

        int pageBatchSize(int remaining) {
            return Math.max(1, Math.min(Math.max(1, prefetchCount), remaining));
        }

        List<H2CacheItem> liveItems() {
            if (iterateSize == 0) {
                return Collections.emptyList();
            }

            Map<Object, H2CacheItem> visibleByKey = new HashMap<>();
            Long cursor = null;
            int batchSize = pageBatchSize(Integer.MAX_VALUE);
            while (true) {
                List<H2CacheItem> page = fetchPageAfterId(cursor, batchSize);
                if (page.isEmpty()) {
                    break;
                }
                for (H2CacheItem item : page) {
                    Object physicalKey = item.getKey();
                    if (!matchesPhysicalKey(physicalKey)) {
                        continue;
                    }
                    H2CacheItem visible = persistedVisibleItem(physicalKey, item);
                    if (visible != null) {
                        visibleByKey.put(physicalKey, visible);
                    }
                }
                if (page.size() < batchSize) {
                    break;
                }
                cursor = page.get(page.size() - 1).getId();
            }

            long nowEpoch = epoch.get();
            for (PendingOp op : new ArrayList<>(pendingLatest.values())) {
                if (op == null) {
                    continue;
                }
                if (op.epoch != nowEpoch) {
                    pendingLatest.remove(op.physicalKey, op);
                    continue;
                }
                if (!matchesPhysicalKey(op.physicalKey)) {
                    continue;
                }
                H2CacheItem visible = pendingVisibleItem(op);
                if (visible == null) {
                    visibleByKey.remove(op.physicalKey);
                } else {
                    visibleByKey.put(op.physicalKey, visible);
                }
            }

            List<H2CacheItem> items = new ArrayList<>(visibleByKey.values());
            items.sort(Comparator.comparingLong(H2CacheItem::getId));
            if (offset >= items.size()) {
                return Collections.emptyList();
            }
            int toIndex = Math.min(items.size(), offset + iterateSize);
            return new ArrayList<>(items.subList(offset, toIndex));
        }

        H2CacheItem persistedVisibleItem(Object physicalKey, H2CacheItem item) {
            if (item == null || item.isTombstone()) {
                return null;
            }
            if (item.isExpired()) {
                scheduleExpiredRemove(physicalKey, item);
                return null;
            }
            return item;
        }

        H2CacheItem pendingVisibleItem(PendingOp op) {
            if (op.type == PendingOpType.REMOVE) {
                return null;
            }
            H2CacheItem item = op.itemSnapshot;
            if (item == null || item.isTombstone()) {
                return null;
            }
            if (item.isExpired()) {
                scheduleExpiredRemove(op.physicalKey, item);
                return null;
            }
            return item;
        }

        boolean matchesPhysicalKey(Object physicalKey) {
            if (keyPrefix == null) {
                return true;
            }
            if (!(physicalKey instanceof Tuple)) {
                return false;
            }
            return Objects.equals(((Tuple<?, ?>) physicalKey).left, keyPrefix);
        }

        boolean matchesViewEntry(H2CacheItem item, Map.Entry<?, ?> entry) {
            Object viewKey = keyPrefix == null ? item.getKey() : unwrapPhysicalKey(item.getKey(), keyPrefix);
            return Objects.equals(viewKey, entry.getKey()) && Objects.equals(item.getValue(), entry.getValue());
        }

        public Set<TK> keys() {
            return keyView;
        }

        @Override
        public int size() {
            return liveItems().size();
        }

        @Override
        public boolean contains(Object key) {
            if (!(key instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) key;
            if (entry.getKey() == null) {
                return false;
            }
            H2CacheItem<TK, TV> item = H2StoreCache.this.resolveVisibleItem(toPhysicalKey(entry.getKey()), false);
            return item != null && !item.isTombstone() && matchesViewEntry(item, entry);
        }

        @Override
        public boolean add(Entry<TK, TV> entry) {
            return putKey(entry.getKey(), entry.getValue()) == null;
        }

        @Override
        public boolean remove(Object key) {
            if (!(key instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) key;
            if (entry.getKey() == null) {
                return false;
            }
            Object physicalKey = toPhysicalKey(entry.getKey());
            H2CacheItem<TK, TV> item = H2StoreCache.this.resolveVisibleItem(physicalKey, false);
            if (item == null || item.isTombstone() || !matchesViewEntry(item, entry)) {
                return false;
            }
            return H2StoreCache.this.removePhysicalKey(physicalKey) != null;
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
    long expungePeriod = DEFAULT_EXPUNGE_PERIOD_MILLIS;
    @Setter
    long tombstoneExpireMillis = DEFAULT_TOMBSTONE_EXPIRE_MILLIS;
    @Setter
    long flushTimeoutMillis = DEFAULT_FLUSH_TIMEOUT_MILLIS;
    @Setter
    long retryDelayMillis = DEFAULT_RETRY_DELAY_MILLIS;
    final EntrySetView setView = new EntrySetView();
    final MemoryCache<Object, H2CacheItem> l1Cache;
    final long l1CacheMaxSize;
    final Set<Object> renewingKeys = ConcurrentHashMap.newKeySet();
    final Set<Object> processingKeys = ConcurrentHashMap.newKeySet();
    final ConcurrentHashMap<Object, PendingOp> pendingLatest = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Object, CompletableFuture<LoadResult>> loadingMap = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Object, Long> persistedSeq = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Object, ConcurrentLinkedQueue<FlushWaiter>> flushWaiters = new ConcurrentHashMap<>();
    final AtomicLong sequence = new AtomicLong();
    final AtomicLong epoch = new AtomicLong();
    final ReentrantReadWriteLock dbLock = new ReentrantReadWriteLock();
    final List<StripeState> stripes;
    final int stripeCount;
    final int stripeMask;
    volatile ScheduledFuture<?> expungeTask;
    volatile boolean closed;

    public H2StoreCache() {
        this(EntityDatabase.DEFAULT, DEFAULT_L1_CACHE_MAX_SIZE, DEFAULT_STRIPE_COUNT);
    }

    public H2StoreCache(@NonNull EntityDatabase db) {
        this(db, DEFAULT_L1_CACHE_MAX_SIZE, DEFAULT_STRIPE_COUNT);
    }

    public H2StoreCache(@NonNull EntityDatabase db, long l1CacheMaxSize) {
        this(db, l1CacheMaxSize, DEFAULT_STRIPE_COUNT);
    }

    public H2StoreCache(@NonNull EntityDatabase db, long l1CacheMaxSize, int stripeCount) {
        if (l1CacheMaxSize <= 0) {
            throw new IllegalArgumentException("l1CacheMaxSize must be > 0");
        }
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be > 0");
        }
        db.createMapping(H2CacheItem.class);
        this.db = db;
        this.l1CacheMaxSize = l1CacheMaxSize;
        l1Cache = new MemoryCache<>(b -> b.maximumSize(l1CacheMaxSize));

        this.stripeCount = roundUpToPowerOfTwo(stripeCount);
        stripes = new ArrayList<>(this.stripeCount);
        stripeMask = this.stripeCount - 1;
        int cacheId = CACHE_COUNTER.incrementAndGet();
        for (int i = 0; i < this.stripeCount; i++) {
            stripes.add(new StripeState(cacheId, i));
        }

        scheduleExpungeTask();
    }

    public void setExpungePeriod(long expungePeriod) {
        ensureOpen();
        if (expungePeriod <= 0) {
            throw new IllegalArgumentException("expungePeriod must be > 0");
        }
        this.expungePeriod = expungePeriod;
        scheduleExpungeTask();
    }

    // region core api
    void expungeStale() {
        if (closed) {
            return;
        }
        long expireBefore = System.currentTimeMillis();
        int batchSize = Math.max(1, prefetchCount);
        ExpungeCursor cursor = null;
        while (true) {
            List<H2CacheItem> stales = findExpiredPage(cursor, expireBefore, batchSize);
            if (stales.isEmpty()) {
                break;
            }
            for (H2CacheItem stale : stales) {
                scheduleExpiredRemove(stale.getKey(), stale);
            }
            if (stales.size() < batchSize) {
                break;
            }
            H2CacheItem tail = stales.get(stales.size() - 1);
            cursor = new ExpungeCursor(tail.getExpiration(), tail.getId());
        }
    }

    @Override
    public int size() {
        ensureOpen();
        return countPersisted(new EntityQueryLambda<>(H2CacheItem.class));
    }

    @Override
    public boolean containsKey(Object key) {
        ensureOpen();
        return containsPhysicalKey(requireKey(key));
    }

    boolean containsPhysicalKey(Object key) {
        H2CacheItem<TK, TV> item = resolveVisibleItem(key, false);
        return item != null && !item.isTombstone();
    }

    @Override
    public boolean containsValue(Object value) {
        ensureOpen();
        long valueHash = CodecUtil.hash64(value);
        Long cursor = null;
        int batchSize = Math.max(1, prefetchCount);
        while (true) {
            List<H2CacheItem> candidates = findValueHashPage(valueHash, cursor, batchSize);
            if (candidates.isEmpty()) {
                return false;
            }
            for (H2CacheItem candidate : candidates) {
                if (!Objects.equals(value, candidate.getValue())) {
                    continue;
                }
                H2CacheItem<TK, TV> visible = resolveVisibleItem(candidate.getKey(), false);
                if (visible != null && !visible.isTombstone() && Objects.equals(value, visible.getValue())) {
                    return true;
                }
            }
            if (candidates.size() < batchSize) {
                return false;
            }
            cursor = candidates.get(candidates.size() - 1).getId();
        }
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV get(Object key) {
        ensureOpen();
        H2CacheItem<TK, TV> item = resolveVisibleItem(requireKey(key), true);
        return item == null || item.isTombstone() ? null : item.getValue();
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV put(TK key, TV value, CachePolicy policy) {
        ensureOpen();
        requireKey(key);
        return putPhysicalKey(key, key, value, policy, buildRegion(key.getClass(), null));
    }

    public TV syncPut(TK key, TV value, CachePolicy policy) {
        ensureOpen();
        requireKey(key);
        WriteResult<TV> result = submitPut(key, key, value, policy, buildRegion(key.getClass(), null), true);
        awaitFlush(result.op.physicalKey, result.op.seq);
        return result.oldValue;
    }

    public void fastPut(TK key, TV value) {
        fastPut(key, value, null);
    }

    public void fastPut(TK key, TV value, CachePolicy policy) {
        ensureOpen();
        requireKey(key);
        submitPut(key, key, value, policy, buildRegion(key.getClass(), null), false);
    }

    public void fastPut(String keyPrefix, TK key, TV value) {
        fastPut(keyPrefix, key, value, null);
    }

    public void fastPut(String keyPrefix, TK key, TV value, CachePolicy policy) {
        ensureOpen();
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
        ensureOpen();
        return removePhysicalKey(requireKey(key));
    }

    public TV syncRemove(Object key) {
        ensureOpen();
        requireKey(key);
        WriteResult<TV> result = submitRemove(key, true);
        if (result.op != null) {
            awaitFlush(result.op.physicalKey, result.op.seq);
        }
        return result.oldValue;
    }

    public void fastRemove(Object key) {
        ensureOpen();
        requireKey(key);
        submitRemove(key, false);
    }

    @SuppressWarnings(NON_UNCHECKED)
    TV removePhysicalKey(Object key) {
        return submitRemove(key, true).oldValue;
    }

    @Override
    public void clear() {
        ensureOpen();
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
        ensureOpen();
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
        ensureOpen();
        requireKey(key);
        PendingOp op = pendingLatest.get(key);
        if (op == null) {
            return;
        }
        awaitFuture(registerWaiter(key, op.seq), key, op.seq);
    }

    public long pendingWriteCount() {
        ensureOpen();
        return pendingLatest.size();
    }

    public long l1CacheMaxSize() {
        ensureOpen();
        return l1CacheMaxSize;
    }

    public long l1EstimatedSize() {
        ensureOpen();
        return l1Cache.cache.estimatedSize();
    }

    public int stripeCount() {
        ensureOpen();
        return stripeCount;
    }

    public int pendingQueueSize() {
        ensureOpen();
        int size = 0;
        for (StripeState stripe : stripes) {
            size += stripe.queue.size();
        }
        return size;
    }

    public int pendingQueueSize(int stripeIndex) {
        ensureOpen();
        if (stripeIndex < 0 || stripeIndex >= stripeCount) {
            throw new IllegalArgumentException("stripeIndex out of range");
        }
        return stripes.get(stripeIndex).queue.size();
    }

    @Override
    public Set<Entry<TK, TV>> entrySet() {
        ensureOpen();
        return setView;
    }

    public EntrySetView entrySet(int offset, int size) {
        ensureOpen();
        return new EntrySetView(null, offset, size);
    }

    public EntrySetView entrySet(String keyPrefix) {
        return entrySet(keyPrefix, 0, DEFAULT_ITERATOR_SIZE);
    }

    public EntrySetView entrySet(String keyPrefix, int offset, int size) {
        ensureOpen();
        if (keyPrefix == null && offset == 0 && size == Integer.MAX_VALUE) {
            return setView;
        }
        return new EntrySetView(keyPrefix, offset, size);
    }

    @Override
    public Set<TK> asSet() {
        ensureOpen();
        return setView.keys();
    }

    public Set<TK> asSet(String keyPrefix) {
        return entrySet(keyPrefix, 0, Integer.MAX_VALUE).keys();
    }

    public Set<TK> asSet(String keyPrefix, int offset, int size) {
        ensureOpen();
        return entrySet(keyPrefix, offset, size).keys();
    }

    public Iterator<Entry<TK, TV>> iterator(int offset, int size) {
        ensureOpen();
        return entrySet(offset, size).iterator();
    }

    public Iterator<Entry<TK, TV>> iterator(String keyPrefix, int offset, int size) {
        ensureOpen();
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
        PendingOp op = new PendingOp(key, seq, opEpoch, PendingOpType.REMOVE, tombstone);
        op.expiredEventEntry = snapshotEntry(item);
        PendingOp installed = installDerivedOp(op, observedVersion);
        if (installed == op) {
            l1Cache.put(key, tombstone, tombstone);
            renewingKeys.remove(key);
            enqueueKey(key);
        }
    }

    void scheduleRenew(Object key, H2CacheItem<TK, TV> item) {
        if (item == null || item.isTombstone() || !item.isSliding()) {
            return;
        }
        PendingOp pending = pendingLatest.get(key);
        if (pending != null && pending.epoch == epoch.get()) {
            if (pending.type == PendingOpType.REMOVE) {
                return;
            }
            if (!item.slidingRenew()) {
                return;
            }
            l1Cache.put(key, item, item);
            if (processingKeys.contains(key)) {
                pending.rescheduleAfterFlush = true;
            }
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
        if (closed) {
            return;
        }
        stripes.get(stripeIndex(key)).offer(key);
    }

    PendingOp flushPendingKey(Object key) {
        PendingOp op = pendingLatest.get(key);
        if (op == null) {
            return null;
        }
        if (op.epoch != epoch.get()) {
            pendingLatest.remove(key, op);
            return op;
        }

        DeleteResult deleteResult = DeleteResult.COMMITTED;
        try {
            PendingOp latest = pendingLatest.get(key);
            if (latest != null && latest.epoch == op.epoch && latest.seq > op.seq) {
                return op;
            }
            if (op.type != PendingOpType.REMOVE && op.itemSnapshot.isExpired()) {
                scheduleExpiredRemove(key, op.itemSnapshot);
                return op;
            }

            dbLock.readLock().lock();
            try {
                if (op.epoch != epoch.get()) {
                    return op;
                }
                latest = pendingLatest.get(key);
                if (latest != null && latest.epoch == op.epoch && latest.seq > op.seq) {
                    return op;
                }
                switch (op.type) {
                    case PUT:
                    case RENEW:
                        op.itemSnapshot.setTombstone(false);
                        db.save(op.itemSnapshot);
                        break;
                    case REMOVE:
                        deleteResult = deletePersistedIfMatched(key, op.seq);
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
            return op;
        }

        if (op.type == PendingOpType.REMOVE && op.expiredEventEntry != null && deleteResult != DeleteResult.SKIPPED_NEWER) {
            raiseEvent(onExpired, castEntry(op.expiredEventEntry));
        }

        persistedSeq.merge(key, op.seq, Math::max);
        completeWaiters(key, op.seq);

        PendingOp current = pendingLatest.get(key);
        if (current != null && current.seq == op.seq && current.epoch == op.epoch) {
            if (!op.rescheduleAfterFlush) {
                pendingLatest.remove(key, current);
            }
        }
        return op;
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

    DeleteResult deletePersistedIfMatched(Object key, long maxVersion) {
        H2CacheItem<TK, TV> item = findPersisted(key);
        if (item == null) {
            return DeleteResult.COMMITTED;
        }
        if (item.getVersion() > maxVersion) {
            return DeleteResult.SKIPPED_NEWER;
        }
        db.deleteById(H2CacheItem.class, item.getId());
        return DeleteResult.COMMITTED;
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

    List<H2CacheItem> findExpiredPage(ExpungeCursor cursor, long expireBefore, int limit) {
        if (cursor == null) {
            return findPersistedItems(new EntityQueryLambda<>(H2CacheItem.class)
                    .le(H2CacheItem::getExpiration, expireBefore)
                    .orderBy(H2CacheItem::getExpiration)
                    .orderBy(H2CacheItem::getId)
                    .limit(limit));
        }

        List<H2CacheItem> page = new ArrayList<>(limit);
        page.addAll(findPersistedItems(new EntityQueryLambda<>(H2CacheItem.class)
                .eq(H2CacheItem::getExpiration, cursor.expiration)
                .gt(H2CacheItem::getId, cursor.id)
                .orderBy(H2CacheItem::getId)
                .limit(limit)));
        if (page.size() < limit) {
            page.addAll(findPersistedItems(new EntityQueryLambda<>(H2CacheItem.class)
                    .le(H2CacheItem::getExpiration, expireBefore)
                    .gt(H2CacheItem::getExpiration, cursor.expiration)
                    .orderBy(H2CacheItem::getExpiration)
                    .orderBy(H2CacheItem::getId)
                    .limit(limit - page.size())));
        }
        return page;
    }

    List<H2CacheItem> findValueHashPage(long valueHash, Long afterId, int limit) {
        EntityQueryLambda<H2CacheItem> q = new EntityQueryLambda<>(H2CacheItem.class)
                .eq(H2CacheItem::getValIdx, valueHash)
                .orderBy(H2CacheItem::getId)
                .limit(limit);
        if (afterId != null) {
            q.gt(H2CacheItem::getId, afterId);
        }
        return findPersistedItems(q);
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

    boolean shouldRequeue(Object key, PendingOp processedOp) {
        if (closed) {
            return false;
        }
        PendingOp current = pendingLatest.get(key);
        if (current == null) {
            return false;
        }
        if (current != processedOp) {
            return true;
        }
        if (processedOp.rescheduleAfterFlush) {
            processedOp.rescheduleAfterFlush = false;
            return true;
        }
        return false;
    }

    synchronized void scheduleExpungeTask() {
        if (closed) {
            return;
        }
        ScheduledFuture<?> task = expungeTask;
        if (task != null) {
            task.cancel(false);
        }
        expungeTask = Tasks.schedulePeriod(this::expungeStale, expungePeriod);
    }

    void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("cache closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScheduledFuture<?> task = expungeTask;
        expungeTask = null;
        if (task != null) {
            task.cancel(false);
        }
        for (StripeState stripe : stripes) {
            stripe.shutdown();
        }
        dbLock.writeLock().lock();
        try {
            failAllWaiters(new IllegalStateException("cache closed"));
            pendingLatest.clear();
            loadingMap.clear();
            persistedSeq.clear();
            renewingKeys.clear();
            l1Cache.clear();
        } finally {
            dbLock.writeLock().unlock();
        }
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

    @SuppressWarnings(NON_UNCHECKED)
    Map.Entry<TK, TV> castEntry(Map.Entry<?, ?> entry) {
        return (Map.Entry<TK, TV>) entry;
    }

    Map.Entry<Object, Object> snapshotEntry(H2CacheItem<?, ?> item) {
        return new AbstractMap.SimpleEntry<Object, Object>(item.getKey(), item.getValue());
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
