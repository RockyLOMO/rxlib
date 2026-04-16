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
import java.util.concurrent.ConcurrentHashMap;

import static org.rx.bean.$.$;
import static org.rx.core.Constants.NON_UNCHECKED;

@Slf4j
public class H2StoreCache<TK, TV> implements Cache<TK, TV>, EventPublisher<H2StoreCache<TK, TV>> {
    public class EntrySetView extends AbstractSet<Entry<TK, TV>> {
        final String keyPrefix;
        final KeySetView keyView = new KeySetView();

        EntrySetView() {
            this(null);
        }

        EntrySetView(String keyPrefix) {
            this.keyPrefix = keyPrefix;
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
            final int[] state = {0, 0};
            List<H2CacheItem> firstPage = db.findBy(newQuery().limit(0, prefetchCount));
            if (firstPage.isEmpty()) {
                return IteratorUtils.emptyIterator();
            }
            state[0] = firstPage.size();
            return new AbstractSequentialIterator<H2CacheItem>(firstPage.get(0)) {
                List<H2CacheItem> page = firstPage;
                H2CacheItem current;

                @Override
                protected H2CacheItem computeNext(H2CacheItem previous) {
                    current = previous;
                    while (true) {
                        if (++state[1] < page.size()) {
                            return page.get(state[1]);
                        }
                        page = db.findBy(newQuery().limit(state[0], prefetchCount));
                        if (page.isEmpty()) {
                            return null;
                        }
                        state[0] += page.size();
                        state[1] = 0;
                        return page.get(0);
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
            return keyPrefix == null ? H2StoreCache.this.size() : (int) db.count(newQuery());
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
            return wrapPhysicalKey(key, keyPrefix);
        }

        TV putKey(TK key, TV value) {
            if (key == null) {
                return null;
            }
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
    final EntrySetView setView = new EntrySetView();
    final MemoryCache<Object, H2CacheItem> l1Cache = new MemoryCache<>(b -> b.maximumSize(2048));
    final Set<Object> renewingKeys = ConcurrentHashMap.newKeySet();

    public H2StoreCache() {
        this(EntityDatabase.DEFAULT);
    }

    public H2StoreCache(@NonNull EntityDatabase db) {
        db.createMapping(H2CacheItem.class);
        this.db = db;
        Tasks.schedulePeriod(this::expungeStale, expungePeriod);
    }

    void expungeStale() {
        while (true) {
            List<H2CacheItem> stales = db.findBy(new EntityQueryLambda<>(H2CacheItem.class)
                    .le(H2CacheItem::getExpiration, System.currentTimeMillis())
                    .limit(prefetchCount));
            if (stales.isEmpty()) {
                break;
            }
            for (H2CacheItem stale : stales) {
                l1Cache.remove(stale.getKey());
                db.deleteById(H2CacheItem.class, stale.id);
                raiseEvent(onExpired, stale);
            }
            if (stales.size() < prefetchCount) {
                break;
            }
        }
    }

    @Override
    public int size() {
        return (int) db.count(new EntityQueryLambda<>(H2CacheItem.class));
    }

    @Override
    public boolean containsKey(Object key) {
        return containsPhysicalKey(key);
    }

    boolean containsPhysicalKey(Object key) {
        H2CacheItem<TK, TV> item = getL1(key);
        if (item != null) {
            if (item.isExpired()) {
                l1Cache.remove(key);
                db.deleteById(H2CacheItem.class, item.id);
                raiseEvent(onExpired, item);
                return false;
            }
            return true;
        }
        item = findPersisted(key);
        if (item == null) {
            return false;
        }
        if (item.isExpired()) {
            db.deleteById(H2CacheItem.class, item.id);
            raiseEvent(onExpired, item);
            return false;
        }
        l1Cache.put(key, item, item);
        return true;
    }

    @Override
    public boolean containsValue(Object value) {
        return db.exists(new EntityQueryLambda<>(H2CacheItem.class)
                .eq(H2CacheItem::getValIdx, CodecUtil.hash64(value)));
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV get(Object key) {
        H2CacheItem<TK, TV> item = getL1(key);
        if (item != null) {
            if (item.isExpired()) {
                l1Cache.remove(key);
                db.deleteById(H2CacheItem.class, item.id);
                raiseEvent(onExpired, item);
                return null;
            }
            if (item.slidingRenew()) {
                renewAsync(key, item);
            }
            return item.getValue();
        }

        item = findPersisted(key);
        if (item == null) {
            return null;
        }
        if (item.isExpired()) {
            db.deleteById(H2CacheItem.class, item.id);
            raiseEvent(onExpired, item);
            return null;
        }
        if (item.slidingRenew()) {
            renewAsync(item.getKey(), item);
        }
        l1Cache.put((TK) key, item, item);
        return item.getValue();
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV put(TK key, TV value, CachePolicy policy) {
        return putPhysicalKey(key, key, value, policy, buildRegion(key.getClass(), null));
    }

    public void fastPut(TK key, TV value) {
        fastPut(key, value, null);
    }

    public void fastPut(TK key, TV value, CachePolicy policy) {
        if (key == null) {
            return;
        }
        fastPutPhysicalKey(key, value, policy, buildRegion(key.getClass(), null));
    }

    public void fastPut(String keyPrefix, TK key, TV value) {
        fastPut(keyPrefix, key, value, null);
    }

    public void fastPut(String keyPrefix, TK key, TV value, CachePolicy policy) {
        if (key == null) {
            return;
        }
        fastPutPhysicalKey(wrapPhysicalKey(key, keyPrefix), value, policy, buildRegion(key.getClass(), keyPrefix));
    }

    @SuppressWarnings(NON_UNCHECKED)
    TV putPhysicalKey(Object physicalKey, Object logicalKey, TV value, CachePolicy policy, String region) {
        if (policy == null) {
            policy = CachePolicy.absolute(defaultExpireSeconds);
        }

        H2CacheItem<TK, TV> oldItem = getL1(physicalKey);
        if (oldItem == null) {
            oldItem = findPersisted(physicalKey);
        }
        TV oldValue = oldItem != null ? oldItem.getValue() : null;

        H2CacheItem<Object, TV> newItem = new H2CacheItem<>(physicalKey, value, policy);
        newItem.setRegion(region != null ? region : buildRegion(logicalKey.getClass(), null));

        l1Cache.put(physicalKey, newItem, newItem);
        db.save(newItem);
        return oldValue;
    }

    void fastPutPhysicalKey(Object physicalKey, TV value, CachePolicy policy, String region) {
        if (policy == null) {
            policy = CachePolicy.absolute(defaultExpireSeconds);
        }

        H2CacheItem<Object, TV> newItem = new H2CacheItem<>(physicalKey, value, policy);
        newItem.setRegion(region);
        l1Cache.put(physicalKey, newItem, newItem);
        db.save(newItem);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public TV remove(Object key) {
        return removePhysicalKey(key);
    }

    @SuppressWarnings(NON_UNCHECKED)
    TV removePhysicalKey(Object key) {
        H2CacheItem<TK, TV> removed = getL1(key);
        l1Cache.remove(key);
        TV val = removed != null ? removed.getValue() : null;
        H2CacheItem<TK, TV> item = findPersisted(key);
        if (item == null) {
            return val;
        }
        db.deleteById(H2CacheItem.class, item.id);
        if (val == null) {
            val = item.getValue();
        }
        return val;
    }

    @Override
    public void clear() {
        l1Cache.clear();
        db.truncateMapping(H2CacheItem.class);
    }

    @Override
    public Set<Entry<TK, TV>> entrySet() {
        return setView;
    }

    public EntrySetView entrySet(String keyPrefix) {
        return keyPrefix == null ? setView : new EntrySetView(keyPrefix);
    }

    @Override
    public Set<TK> asSet() {
        return setView.keys();
    }

    public Set<TK> asSet(String keyPrefix) {
        return entrySet(keyPrefix).keys();
    }

    void renewAsync(Object key, H2CacheItem<TK, TV> item) {
        if (!renewingKeys.add(key)) {
            return;
        }
        Tasks.run(() -> {
            try {
                db.save(item);
            } finally {
                renewingKeys.remove(key);
            }
        });
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

    static Object wrapPhysicalKey(Object key, String keyPrefix) {
        if (keyPrefix == null) {
            return key;
        }
        return Tuple.of(keyPrefix, key);
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
