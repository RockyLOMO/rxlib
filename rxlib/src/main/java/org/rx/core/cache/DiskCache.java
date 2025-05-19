package org.rx.core.cache;

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

import static org.rx.bean.$.$;
import static org.rx.core.Constants.NON_UNCHECKED;

@Slf4j
public class DiskCache<TK, TV> implements Cache<TK, TV>, EventPublisher<DiskCache<TK, TV>> {
    public class EntrySetView extends AbstractSet<Entry<TK, TV>> {
        @Override
        public Iterator<Map.Entry<TK, TV>> iterator() {
            return iterator(0, Integer.MAX_VALUE);
        }

        public Iterator<Map.Entry<TK, TV>> iterator(int offset, int size) {
            //1 = readPos, 2 = remaining
            final int[] wrap = {offset, 0, size};
            $<List<H2CacheItem>> buf = $();
            int readSize = Math.min(prefetchCount, wrap[2]);
            buf.v = db.findBy(new EntityQueryLambda<>(H2CacheItem.class)
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
                            buf.v = db.findBy(new EntityQueryLambda<>(H2CacheItem.class)
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
                    DiskCache.this.remove(current.getKey());
                }
            };
        }

        @Override
        public int size() {
            return DiskCache.this.size();
        }

        @Override
        public boolean contains(Object key) {
            return DiskCache.this.containsKey(key);
        }

        @Override
        public boolean add(Entry<TK, TV> entry) {
            return DiskCache.this.put(entry.getKey(), entry.getValue()) == null;
        }

        @Override
        public boolean remove(Object key) {
            return DiskCache.this.remove(key) != null;
        }
    }

    public static final DiskCache<?, ?> DEFAULT;

    static {
        IOC.register(DiskCache.class, DEFAULT = new DiskCache<>());
    }

    public final Delegate<DiskCache<TK, TV>, Map.Entry<TK, TV>> onExpired = Delegate.create();
    final EntityDatabase db;
    @Setter
    int defaultExpireSeconds = 60 * 60 * 24 * 90;  //3 months
    @Setter
    int prefetchCount = 100;
    @Setter
    long expungePeriod = 1000 * 60;
    EntrySetView setView;

    public DiskCache() {
        this(EntityDatabase.DEFAULT);
    }

    public DiskCache(@NonNull EntityDatabase db) {
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
    public EntrySetView entrySet() {
        if (setView == null) {
            setView = new EntrySetView();
        }
        return setView;
    }
}
