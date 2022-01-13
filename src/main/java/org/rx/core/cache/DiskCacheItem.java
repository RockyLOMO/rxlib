package org.rx.core.cache;

import org.rx.bean.DateTime;
import org.rx.core.CachePolicy;

import java.io.Serializable;

class DiskCacheItem<TV> extends CachePolicy implements Serializable {
    private static final long serialVersionUID = -7742074465897857966L;
    final TV value;

    public DateTime getExpire() {
        return absoluteExpiration == null ? DateTime.MAX : absoluteExpiration;
    }

    public void setExpire(DateTime time) {
        absoluteExpiration = time;
    }

    public boolean isExpired() {
        return getExpire().before(DateTime.utcNow());
    }

    public DiskCacheItem(TV value, CachePolicy policy) {
        super(policy);
        this.value = value;
    }
}
