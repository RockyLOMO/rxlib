package org.rx.core.cache;

import org.rx.core.CachePolicy;

class DiskCacheItem<TV> extends CachePolicy {
    private static final long serialVersionUID = -7742074465897857966L;
    final TV value;

    public DiskCacheItem(TV value, CachePolicy policy) {
        super(policy);
        this.value = value;
    }
}
