package org.rx.core.cache;

import io.netty.util.concurrent.FastThreadLocal;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.IteratorUtils;
import org.rx.bean.$;
import org.rx.codec.CodecUtil;
import org.rx.core.*;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;
import org.rx.third.guava.AbstractSequentialIterator;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.rx.bean.$.$;
import static org.rx.core.Constants.NON_UNCHECKED;

@Slf4j
public class H2StoreCache<TK, TV> implements Cache<TK, TV>, EventPublisher<H2StoreCache<TK, TV>> {
    class EntrySetView extends AbstractSet<Entry<TK, TV>> {
        @Override
        public Iterator<Map.Entry<TK, TV>> iterator() {
            Object[] iteCtx = ITERATOR_CTX.get();
            boolean noCtx = iteCtx == null;
            int offset = noCtx ? 0 : (int) iteCtx[0];
            int size = noCtx ? Integer.MAX_VALUE : (int) iteCtx[1];
            Class<?> keyType = noCtx ? null : (Class<?>) iteCtx[2];

            //1 = readPos, 2 = remaining
            final int[] wrap = {offset, 0, size};
            $<List<H2CacheItem>> buf = $();
            int readSize = Math.min(prefetchCount, wrap[2]);
            buf.v = db.findBy(newQuery(keyType)
                    .limit(wrap[0], readSize));
            if (buf.v.isEmpty()) {
                return IteratorUtils.emptyIterator();
            }
            wrap[0] += readSize;
            return new AbstractSequentialIterator<Entry<TK, TV>>(buf.v.get(wrap[1])) {
                Map.Entry<TK, TV> current;

                @Override
                protected Entry<TK, TV> computeNext(Entry<TK, TV> previous) {
                    current = previous;
                    if (--wrap[2] <= 0) {
                        return null;
                    }
                    while (true) {
                        if (++wrap[1] == buf.v.size()) {
                            int nextSize = Math.min(prefetchCount, wrap[2]);
                            buf.v = db.findBy(newQuery(keyType)
                                    .limit(wrap[0], nextSize));
                            if (buf.v.isEmpty()) {
                                return null;
                            }
                            wrap[0] += nextSize;
                            wrap[1] = 0;
                        }
                        return buf.v.get(wrap[1]);
                    }
                }

                @Override
                public void remove() {
                    H2StoreCache.this.remove(current.getKey());
                }
            };
        }

        EntityQueryLambda<H2CacheItem> newQuery(Class<?> keyType) {
            EntityQueryLambda<H2CacheItem> q = new EntityQueryLambda<>(H2CacheItem.class);
            if (keyType != null) {
                q.eq(H2CacheItem::getRegion, keyType.getSimpleName());
            }
            return q;
        }

        @Override
        public int size() {
            return H2StoreCache.this.size();
        }

        @Override
        public boolean contains(Object key) {
            return H2StoreCache.this.containsKey(key);
        }

        @Override
        public boolean add(Entry<TK, TV> entry) {
            return H2StoreCache.this.put(entry.getKey(), entry.getValue()) == null;
        }

        @Override
        public boolean remove(Object key) {
            return H2StoreCache.this.remove(key) != null;
        }
    }

    public static final H2StoreCache<?, ?> DEFAULT;
    static final FastThreadLocal<Object[]> ITERATOR_CTX = new FastThreadLocal<>();

    static {
        IOC.register(H2StoreCache.class, DEFAULT = new H2StoreCache<>());
    }

    public static void iteratorContext() {
        iteratorContext(0, Integer.MAX_VALUE, null);
    }

    public static void iteratorContext(int offset, int size, Class<?> keyType) {
        ITERATOR_CTX.set(new Object[]{offset, size, keyType});
    }

    public final Delegate<H2StoreCache<TK, TV>, Map.Entry<TK, TV>> onExpired = Delegate.create();
    final EntityDatabase db;
    @Setter
    int defaultExpireSeconds = 60 * 60 * 24 * 90;  //3 months
    @Setter
    int prefetchCount = 100;
    @Setter
    long expungePeriod = 1000 * 60;
    EntrySetView setView;

    public H2StoreCache() {
        this(EntityDatabase.DEFAULT);
    }

    public H2StoreCache(@NonNull EntityDatabase db) {
        db.createMapping(H2CacheItem.class);
        this.db = db;
        Tasks.schedulePeriod(this::expungeStale, expungePeriod);
    }

    void expungeStale() {
        List<H2CacheItem> stales = db.findBy(new EntityQueryLambda<>(H2CacheItem.class)
                .le(H2CacheItem::getExpiration, System.currentTimeMillis())
                .limit(prefetchCount));
        for (H2CacheItem stale : stales) {
            db.deleteById(H2CacheItem.class, stale.id);
            raiseEvent(onExpired, stale);
        }
    }

    @Override
    public int size() {
        return (int) db.count(new EntityQueryLambda<>(H2CacheItem.class));
    }

    @Override
    public boolean containsKey(Object key) {
        return db.exists(new EntityQueryLambda<>(H2CacheItem.class)
                .eq(H2CacheItem::getId, CodecUtil.hash64(key)));
    }

    @Override
    public boolean containsValue(Object value) {
        return db.exists(new EntityQueryLambda<>(H2CacheItem.class)
                .eq(H2CacheItem::getValIdx, CodecUtil.hash64(value)));
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV get(Object key) {
        H2CacheItem<TK, TV> item = db.findById(H2CacheItem.class, CodecUtil.hash64(key));
        if (item == null) {
            return null;
        }
        if (item.isExpired()) {
            db.deleteById(H2CacheItem.class, item.id);
            raiseEvent(onExpired, item);
            return item.getValue();
        }
        if (item.slidingRenew()) {
            db.save(item);
        }
        return item.getValue();
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV put(TK key, TV value, CachePolicy policy) {
        if (policy == null) {
            policy = CachePolicy.absolute(defaultExpireSeconds);
        }
        TV oldValue;
        H2CacheItem<TK, TV> item = db.findById(H2CacheItem.class, CodecUtil.hash64(key));
        if (item == null) {
            oldValue = null;
            item = new H2CacheItem<>(key, value, policy);
            item.setRegion(key.getClass().getSimpleName());
        } else {
            oldValue = item.getValue();
            item.setValue(value);
        }
        db.save(item);
        return oldValue;
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV remove(Object key) {
        H2CacheItem<TK, TV> item = db.findById(H2CacheItem.class, CodecUtil.hash64(key));
        if (item == null) {
            return null;
        }
        db.deleteById(H2CacheItem.class, item.id);
        return item.getValue();
    }

    @Override
    public void clear() {
        db.truncateMapping(H2CacheItem.class);
    }

    @Override
    public Set<Entry<TK, TV>> entrySet() {
        if (setView == null) {
            setView = new EntrySetView();
        }
        return setView;
    }
}
