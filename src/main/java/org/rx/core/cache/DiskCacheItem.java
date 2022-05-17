package org.rx.core.cache;

import org.rx.core.CachePolicy;

import java.io.Serializable;

class DiskCacheItem<TV> extends CachePolicy implements Serializable {
    private static final long serialVersionUID = -7742074465897857966L;
    final TV value;

    public boolean isExpired() {
        return expiration() <= 0;
    }

    public DiskCacheItem(TV value, CachePolicy policy) {
        super(policy);
        this.value = value;
    }
}
