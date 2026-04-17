package org.rx.core.cache;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.rx.AbstractTester;
import org.rx.bean.DataTable;
import org.rx.codec.CodecUtil;
import org.rx.core.CachePolicy;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class H2StoreCacheTest extends AbstractTester {
    static class TrackingEntityDatabase implements EntityDatabase {
        final ConcurrentHashMap<Long, H2CacheItem<Object, Object>> store = new ConcurrentHashMap<>();
        final AtomicInteger findByIdCalls = new AtomicInteger();
        final AtomicInteger saveCalls = new AtomicInteger();
        final AtomicInteger deleteCalls = new AtomicInteger();
        final AtomicInteger failSaveTimes = new AtomicInteger();
        final AtomicInteger failDeleteTimes = new AtomicInteger();
        volatile boolean blockSave;
        volatile boolean blockFindById;
        CountDownLatch saveStarted = new CountDownLatch(1);
        CountDownLatch allowSave = new CountDownLatch(1);
        CountDownLatch findStarted = new CountDownLatch(1);
        CountDownLatch allowFind = new CountDownLatch(1);

        void resetSaveBlock() {
            saveStarted = new CountDownLatch(1);
            allowSave = new CountDownLatch(1);
        }

        void resetFindBlock() {
            findStarted = new CountDownLatch(1);
            allowFind = new CountDownLatch(1);
        }

        void prime(String key, Object value) {
            H2CacheItem<Object, Object> item = new H2CacheItem<>(key, value, CachePolicy.absolute(300));
            item.setVersion(1);
            store.put(item.getId(), copyOf(item));
        }

        Object persistedValue(Object key) {
            H2CacheItem<Object, Object> item = store.get(CodecUtil.hash64(key));
            return item == null ? null : item.getValue();
        }

        @SuppressWarnings("unchecked")
        H2CacheItem<Object, Object> copyOf(H2CacheItem<?, ?> src) {
            if (src == null) {
                return null;
            }
            H2CacheItem<Object, Object> copy = new H2CacheItem<>(src.getKey(), src.getValue(), src);
            copy.setRegion(src.getRegion());
            copy.setVersion(src.getVersion());
            copy.setTombstone(src.isTombstone());
            return copy;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void save(T entity) {
            save(entity, true);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void save(T entity, boolean doInsert) {
            H2CacheItem<Object, Object> item = (H2CacheItem<Object, Object>) entity;
            saveCalls.incrementAndGet();
            if (blockSave) {
                saveStarted.countDown();
                try {
                    assertTrue(allowSave.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail(e);
                }
            }
            if (failSaveTimes.get() > 0) {
                failSaveTimes.decrementAndGet();
                throw new IllegalStateException("save failed");
            }
            store.put(item.getId(), copyOf(item));
        }

        @Override
        public <T> boolean deleteById(Class<T> entityType, Serializable id) {
            deleteCalls.incrementAndGet();
            if (failDeleteTimes.get() > 0) {
                failDeleteTimes.decrementAndGet();
                throw new IllegalStateException("delete failed");
            }
            return store.remove(id) != null;
        }

        @Override
        public <T> long delete(EntityQueryLambda<T> query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> long count(EntityQueryLambda<T> query) {
            return store.size();
        }

        @Override
        public <T> boolean exists(EntityQueryLambda<T> query) {
            return !store.isEmpty();
        }

        @Override
        public <T> boolean existsById(Class<T> entityType, Serializable id) {
            return store.containsKey(id);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T findById(Class<T> entityType, Serializable id) {
            findByIdCalls.incrementAndGet();
            if (blockFindById) {
                findStarted.countDown();
                try {
                    assertTrue(allowFind.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail(e);
                }
            }
            return (T) copyOf(store.get(id));
        }

        @Override
        public <T> T findOne(EntityQueryLambda<T> query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<T> findBy(EntityQueryLambda<T> query) {
            return Collections.emptyList();
        }

        @Override
        public void compact() {
        }

        @Override
        public <T> void truncateMapping(Class<T> entityType) {
            store.clear();
        }

        @Override
        public <T> void dropMapping(Class<T> entityType) {
            store.clear();
        }

        @Override
        public void createMapping(Class<?>... entityTypes) {
        }

        @Override
        public String tableName(Class<?> entityType) {
            return entityType.getSimpleName();
        }

        @Override
        public <T> DataTable executeQuery(String sql, Class<T> entityType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int executeUpdate(String sql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isInTransaction() {
            return false;
        }

        @Override
        public void begin() {
        }

        @Override
        public void begin(int transactionIsolation) {
        }

        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    public void testL1CacheEviction() {
        H2StoreCache<String, String> cache = new H2StoreCache<>();
        cache.clear();

        int count = 2500;
        for (int i = 0; i < count; i++) {
            cache.put("key" + i, "val" + i);
        }
        cache.flush();
        assertEquals(count, cache.size());

        for (int i = 0; i < count; i++) {
            assertEquals("val" + i, cache.get("key" + i), "Value mismatch for key" + i);
        }
    }

    @Test
    public void testConsistency() {
        H2StoreCache<String, String> cache = new H2StoreCache<>();
        cache.clear();

        String key = "consistentKey";
        cache.put(key, "v1");
        assertEquals("v1", cache.get(key));

        cache.put(key, "v2");
        assertEquals("v2", cache.get(key));

        cache.remove(key);
        assertNull(cache.get(key));
        assertFalse(cache.containsKey(key));
        cache.flush();
        assertEquals(0, cache.size());
    }

    @Test
    public void testHashCollisionFix() {
        H2StoreCache<String, String> cache = new H2StoreCache<>();
        cache.clear();
        cache.syncPut("key1", "val1", null);

        assertEquals("val1", cache.get("key1"));
        assertNull(cache.get("nonExistentKey"));
    }

    @Test
    public void testExpungeStale() throws InterruptedException {
        H2StoreCache<String, String> cache = new H2StoreCache<>();
        cache.clear();
        cache.setDefaultExpireSeconds(2);
        cache.put("expireKey", "expireVal");
        cache.flush();

        assertEquals("expireVal", cache.get("expireKey"));
        Thread.sleep(8000);

        assertNull(cache.get("expireKey"));
        cache.flush();
        assertEquals(0, cache.size());
    }

    @Test
    public void testPutUsesL1OldValueBeforeDbLookup() {
        TrackingEntityDatabase db = new TrackingEntityDatabase();
        H2StoreCache<String, String> cache = new H2StoreCache<>(db);

        assertNull(cache.put("hot-key", "v1"));
        assertEquals(1, db.findByIdCalls.get());

        db.findByIdCalls.set(0);
        assertEquals("v1", cache.put("hot-key", "v2"));
        assertEquals(0, db.findByIdCalls.get());
        assertEquals("v2", cache.get("hot-key"));
    }

    @Test
    public void testConstructorAppliesBalancedDefaults() {
        H2StoreCache<String, String> cache = new H2StoreCache<>(new TrackingEntityDatabase());
        assertEquals(H2StoreCache.DEFAULT_L1_CACHE_MAX_SIZE, cache.l1CacheMaxSize());
        assertEquals(H2StoreCache.DEFAULT_STRIPE_COUNT, cache.stripeCount());
    }

    @Test
    public void testConstructorAppliesCustomL1MaxSizeAndStripeCount() {
        H2StoreCache<String, String> cache = new H2StoreCache<>(new TrackingEntityDatabase(), 64, 3);
        assertTrue(cache.l1Cache.cache.policy().eviction().isPresent());
        assertEquals(64L, cache.l1CacheMaxSize());
        assertEquals(64L, cache.l1Cache.cache.policy().eviction().get().getMaximum());
        assertEquals(4, cache.stripeCount());
    }

    @Test
    public void testSetExpungePeriodReschedulesTask() {
        H2StoreCache<String, String> cache = new H2StoreCache<>(new TrackingEntityDatabase(), 64, 1);
        assertNotNull(cache.expungeTask);

        java.util.concurrent.ScheduledFuture<?> first = cache.expungeTask;
        cache.setExpungePeriod(5000);

        assertTrue(first.isCancelled());
        assertNotSame(first, cache.expungeTask);
    }

    @Test
    public void testCloseStopsBackgroundResources() throws Exception {
        H2StoreCache<String, String> cache = new H2StoreCache<>(new TrackingEntityDatabase(), 64, 1);
        Thread worker = cache.stripes.get(0).worker;
        java.util.concurrent.ScheduledFuture<?> task = cache.expungeTask;

        cache.close();

        for (int i = 0; i < 20 && worker.isAlive(); i++) {
            Thread.sleep(50);
        }

        assertTrue(task.isCancelled());
        assertFalse(worker.isAlive());
        assertThrows(IllegalStateException.class, () -> cache.put("k", "v"));
    }

    @Test
    public void testNullKeyRejected() {
        H2StoreCache<String, String> cache = new H2StoreCache<>(new TrackingEntityDatabase());

        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.put(null, "v"));
        assertThrows(NullPointerException.class, () -> cache.remove(null));
        assertThrows(NullPointerException.class, () -> cache.fastPut("p", null, "v"));
    }

    @Test
    public void testQueueDedupKeepsSinglePendingNodePerKey() throws Exception {
        TrackingEntityDatabase db = new TrackingEntityDatabase();
        H2StoreCache<String, String> cache = new H2StoreCache<>(db, 64, 1);
        cache.setRetryDelayMillis(10);

        db.blockSave = true;
        db.resetSaveBlock();
        cache.fastPut("blocker", "v1");
        assertTrue(db.saveStarted.await(1, TimeUnit.SECONDS));

        cache.fastPut("hot", "v1");
        cache.fastPut("hot", "v2");
        cache.fastPut("hot", "v3");

        assertEquals(1, cache.pendingQueueSize());

        db.allowSave.countDown();
        db.blockSave = false;
        cache.flush("hot");
        assertEquals("v3", db.persistedValue("hot"));
    }

    @Test
    public void testSlidingRenewQueuesSingleAsyncSavePerKey() throws Exception {
        TrackingEntityDatabase db = new TrackingEntityDatabase();
        H2StoreCache<String, String> cache = new H2StoreCache<>(db);
        cache.syncPut("renew-key", "renew-val", CachePolicy.sliding(60));

        db.blockSave = true;
        db.resetSaveBlock();
        assertEquals("renew-val", cache.get("renew-key"));
        assertTrue(db.saveStarted.await(1, TimeUnit.SECONDS));

        for (int i = 0; i < 5; i++) {
            assertEquals("renew-val", cache.get("renew-key"));
        }

        Thread.sleep(100);
        assertEquals(2, db.saveCalls.get());
        db.allowSave.countDown();
        db.blockSave = false;
        cache.flush("renew-key");
    }

    @Test
    public void testGetMissConcurrentPutDoesNotBackfillStaleValue() throws Exception {
        TrackingEntityDatabase db = new TrackingEntityDatabase();
        db.prime("race-put", "v0");
        db.blockFindById = true;
        db.resetFindBlock();

        H2StoreCache<String, String> cache = new H2StoreCache<>(db);
        AtomicReference<String> result = new AtomicReference<>();
        Thread thread = new Thread(() -> result.set(cache.get("race-put")));
        thread.start();

        assertTrue(db.findStarted.await(1, TimeUnit.SECONDS));
        cache.fastPut("race-put", "v1");
        db.allowFind.countDown();
        db.blockFindById = false;
        thread.join(2000);

        assertEquals("v1", result.get());
        assertEquals("v1", cache.get("race-put"));
        cache.flush("race-put");
        assertEquals("v1", db.persistedValue("race-put"));
    }

    @Test
    public void testGetMissConcurrentRemoveDoesNotResurrectStaleValue() throws Exception {
        TrackingEntityDatabase db = new TrackingEntityDatabase();
        db.prime("race-remove", "v0");
        db.blockFindById = true;
        db.resetFindBlock();

        H2StoreCache<String, String> cache = new H2StoreCache<>(db);
        AtomicReference<String> result = new AtomicReference<>();
        Thread thread = new Thread(() -> result.set(cache.get("race-remove")));
        thread.start();

        assertTrue(db.findStarted.await(1, TimeUnit.SECONDS));
        cache.fastRemove("race-remove");
        db.allowFind.countDown();
        db.blockFindById = false;
        thread.join(2000);

        assertNull(result.get());
        assertNull(cache.get("race-remove"));
        cache.flush("race-remove");
        assertNull(db.persistedValue("race-remove"));
    }

    @Test
    public void testFlushPersistsLatestValueAndRetriesOnFailure() {
        TrackingEntityDatabase db = new TrackingEntityDatabase();
        db.failSaveTimes.set(1);
        H2StoreCache<String, String> cache = new H2StoreCache<>(db);
        cache.setRetryDelayMillis(10);

        cache.fastPut("retry-key", "v1");
        cache.flush("retry-key");

        assertEquals("v1", db.persistedValue("retry-key"));
        assertEquals(0, cache.pendingWriteCount());
    }

    @Test
    public void testDeleteRetryDoesNotDropPendingState() {
        TrackingEntityDatabase db = new TrackingEntityDatabase();
        H2StoreCache<String, String> cache = new H2StoreCache<>(db);
        cache.setRetryDelayMillis(10);
        cache.syncPut("retry-remove", "v1", null);
        db.failDeleteTimes.set(1);

        cache.fastRemove("retry-remove");
        cache.flush("retry-remove");

        assertNull(db.persistedValue("retry-remove"));
        assertEquals(0, cache.pendingWriteCount());
    }

    @Test
    public void testConcurrentMissUsesSingleDbLoadPerKey() throws Exception {
        TrackingEntityDatabase db = new TrackingEntityDatabase();
        db.prime("load-merge", "v0");
        db.blockFindById = true;
        db.resetFindBlock();

        H2StoreCache<String, String> cache = new H2StoreCache<>(db);
        CountDownLatch start = new CountDownLatch(1);
        List<AtomicReference<String>> results = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            AtomicReference<String> ref = new AtomicReference<>();
            results.add(ref);
            Thread thread = new Thread(() -> {
                try {
                    assertTrue(start.await(1, TimeUnit.SECONDS));
                    ref.set(cache.get("load-merge"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail(e);
                }
            });
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        assertTrue(db.findStarted.await(1, TimeUnit.SECONDS));
        Thread.sleep(100);
        db.allowFind.countDown();
        db.blockFindById = false;

        for (Thread thread : threads) {
            thread.join(2000);
        }
        for (AtomicReference<String> result : results) {
            assertEquals("v0", result.get());
        }
        assertEquals(1, db.findByIdCalls.get());
    }

    @Test
    public void testEntrySetReturnsStableView() {
        H2StoreCache<String, String> cache = new H2StoreCache<>(new TrackingEntityDatabase());
        assertSame(cache.entrySet(), cache.entrySet());
    }

    @Test
    public void testEntrySetOffsetAndSize() {
        H2StoreCache<String, Boolean> cache = new H2StoreCache<>();
        String prefix = "h2-page-" + UUID.randomUUID();
        Set<String> set = cache.asSet(prefix);
        try {
            for (int i = 0; i < 6; i++) {
                cache.fastPut(prefix, "key-" + i, Boolean.TRUE);
            }
            cache.flush();

            Set<Map.Entry<String, Boolean>> page = cache.entrySet(prefix, 2, 3);
            assertEquals(3, page.size());

            int count = 0;
            for (Map.Entry<String, Boolean> ignored : page) {
                count++;
            }
            assertEquals(3, count);
        } finally {
            set.clear();
            cache.flush();
        }
    }

    @Test
    public void testPrefixedAsSetIsolated() {
        H2StoreCache<String, Boolean> cache = new H2StoreCache<>();
        String prefixA = "h2-prefix-a-" + UUID.randomUUID();
        String prefixB = "h2-prefix-b-" + UUID.randomUUID();
        Set<String> setA = cache.asSet(prefixA);
        Set<String> setB = cache.asSet(prefixB);

        try {
            assertTrue(setA.add("same-key"));
            assertTrue(setA.contains("same-key"));
            assertFalse(setB.contains("same-key"));
            cache.flush();
            assertEquals(1, cache.entrySet(prefixA).size());
            assertEquals(0, cache.entrySet(prefixB).size());

            assertTrue(setB.add("same-key"));
            cache.flush();
            assertEquals(1, cache.entrySet(prefixA).size());
            assertEquals(1, cache.entrySet(prefixB).size());
            assertEquals("same-key", cache.entrySet(prefixA).iterator().next().getKey());
        } finally {
            setA.clear();
            setB.clear();
            cache.flush();
            assertEquals(0, cache.entrySet(prefixA).size());
            assertEquals(0, cache.entrySet(prefixB).size());
        }
    }
}
