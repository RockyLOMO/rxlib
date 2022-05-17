package org.rx.core.cache;

import org.rx.bean.DateTime;
import org.rx.core.CachePolicy;
import org.rx.core.Constants;

import java.io.Serializable;

class DiskCacheItem<TV> extends CachePolicy implements Serializable {
    private static final long serialVersionUID = -7742074465897857966L;
    final TV value;

    public DateTime getExpire() {
        return absoluteExpiration == Constants.NON_EXPIRE ? DateTime.MAX : new DateTime(absoluteExpiration);
    }

    public void setExpire(DateTime time) {
        absoluteExpiration = time.getTime();
    }

    public boolean isExpired() {
        return getExpire().before(DateTime.now());
    }

    public DiskCacheItem(TV value, CachePolicy policy) {
        super(policy);
        this.value = value;
    }
}
