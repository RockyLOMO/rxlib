package org.rx.cache;

import lombok.Data;
import org.apache.commons.collections4.map.LRUMap;
import org.rx.Logger;
import org.rx.bean.DateTime;

import java.util.Collections;
import java.util.Map;

import static org.rx.Contract.as;
import static org.rx.Contract.require;

public final class LRUCache<TK, TV> {
    @Data
    private class CacheItem {
        private TV value;
        private DateTime createTime;

        public CacheItem(TV value) {
            this.value = value;
            this.createTime = DateTime.utcNow();
        }
    }

    private final Map<TK, CacheItem> cache;

    public LRUCache(int maxSize) {
        cache = Collections.synchronizedMap(new LRUMap<>(maxSize));
    }

    public void add(TK key, TV val) {
        require(key);

        cache.put(key, new CacheItem(val));
    }

    public void remove(TK key){
        remove(key,true);
    }
    public void remove(TK key, boolean destroy){
        require(key);
        CacheItem remove = cache.remove(key);
        if(remove==null){
            return;
        }

        AutoCloseable ac;
        if (destroy && (ac = as(remove.getValue(), AutoCloseable.class)) != null) {
            try {
                ac.close();
            } catch (Exception ex) {
                Logger.error(ex, "Auto close error");
            }
        }
    }
}
