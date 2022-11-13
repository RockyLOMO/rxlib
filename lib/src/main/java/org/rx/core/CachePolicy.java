package org.rx.core;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.rx.bean.DateTime;

import java.io.Serializable;

@AllArgsConstructor
public class CachePolicy implements Serializable {
    private static final long serialVersionUID = 4378825072232415879L;

    public static CachePolicy today() {
        return today(Constants.ONE_DAY_TOTAL_SECONDS);
    }

    public static CachePolicy today(int expireSeconds) {
        DateTime now = DateTime.now(), expire = now.addSeconds(expireSeconds);
        DateTime max = now.setTimeComponent("23:59:59");
        return new CachePolicy((expire.before(max) ? expire : max).getTime(), 0);
    }

    public static CachePolicy absolute(int expireSeconds) {
        return new CachePolicy(DateTime.now().addSeconds(expireSeconds).getTime(), 0);
    }

    public static CachePolicy sliding(int expireSeconds) {
        return new CachePolicy(DateTime.now().addSeconds(expireSeconds).getTime(), expireSeconds * 1000);
    }

    @Getter
    long expiration = Long.MAX_VALUE;
    int slidingSpan;

    public boolean isExpired() {
        return expiration <= System.currentTimeMillis();
    }

    protected CachePolicy(CachePolicy policy) {
        if (policy == null) {
            return;
        }
        this.expiration = policy.expiration;
        this.slidingSpan = policy.slidingSpan;
    }

    public long ttl() {
        return ttl(slidingSpan > 0);
    }

    public long ttl(boolean slidingRenew) {
        long ttl = Math.max(0, expiration - System.currentTimeMillis());
        if (ttl > 0 && slidingRenew) {
            Tasks.setTimeout(this::slidingRenew, 100, this, TimeoutFlag.REPLACE.flags());
//            slidingRenew();
        }
        return ttl;
    }

    public boolean slidingRenew() {
        if (slidingSpan <= 0) {
            return false;
        }

        if ((expiration += slidingSpan) < 0) {
            expiration = Long.MAX_VALUE;
        }
        return true;
    }
}
