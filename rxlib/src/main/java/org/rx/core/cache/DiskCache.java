package org.rx.core.cache;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rx.codec.CodecUtil;
import org.rx.core.*;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.rx.core.Constants.NON_UNCHECKED;

@Slf4j
public class DiskCache<TK, TV> implements Cache<TK, TV>, EventPublisher<DiskCache<TK, TV>> {
    static {
        IOC.register(DiskCache.class, new DiskCache<>());
    }

    public final Delegate<DiskCache<TK, TV>, Map.Entry<TK, TV>> onExpired = Delegate.create();
    final EntityDatabase db = EntityDatabase.DEFAULT;
    @Setter
    int defaultExpireSeconds = 60 * 60 * 24 * 365;  //1 year
    @Setter
    int maxEntrySetSize = 1000;

    public DiskCache() {
        db.createMapping(H2CacheItem.class);
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
            if (onExpired == null) {
                return null;
            }
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
        db.dropMapping(H2CacheItem.class);
    }

    @SuppressWarnings(NON_UNCHECKED)
    @Override
    public Set<Map.Entry<TK, TV>> entrySet() {
        List<H2CacheItem> by = db.findBy(new EntityQueryLambda<>(H2CacheItem.class).limit(maxEntrySetSize));
        return Linq.from(by).<Map.Entry<TK, TV>>cast().toSet();
    }
}
