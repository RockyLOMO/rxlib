package org.rx.core;

import lombok.Getter;
import org.rx.bean.DateTime;
import org.rx.exception.InvalidException;

import java.io.Serializable;

public class CachePolicy implements Serializable {
    private static final long serialVersionUID = 4378825072232415879L;

    public static CachePolicy today() {
        return today(Constants.ONE_DAY_TOTAL_SECONDS);
    }

    public static CachePolicy today(int expireSeconds) {
        DateTime now = DateTime.now(), expire = now.addSeconds(expireSeconds);
        DateTime max = now.setTimePart("23:59:59");
        return new CachePolicy((expire.before(max) ? expire : max).getTime(), 0);
    }

    public static CachePolicy absolute(int expireSeconds) {
        return new CachePolicy(DateTime.now().addSeconds(expireSeconds).getTime(), 0);
    }

    public static CachePolicy sliding(int expireSeconds) {
        return new CachePolicy(DateTime.now().addSeconds(expireSeconds).getTime(), expireSeconds * 1000);
    }

    final int slidingSpan;
    @Getter
//    @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
    long expiration;

    public boolean isExpired() {
        return
//                expiration != Constants.TIMEOUT_INFINITE &&
                expiration < System.currentTimeMillis();
    }

    public boolean isSliding() {
        return slidingSpan > 0;
    }

    public CachePolicy(long expiration, int slidingSpan) {
        if ((this.expiration = expiration) < 0) {
            throw new InvalidException("expiration must ge 0");
        }
        this.slidingSpan = slidingSpan;
    }

    protected CachePolicy(CachePolicy policy) {
        if (policy == null) {
            this.slidingSpan = 0;
            return;
        }
        this.expiration = policy.expiration;
        this.slidingSpan = policy.slidingSpan;
    }

    public long ttl() {
        return ttl(isSliding());
    }

    public long ttl(boolean slidingRenew) {
        long curTime = System.currentTimeMillis();
        long ttl = Math.max(0, expiration - curTime);
        if (slidingRenew && ttl > 0) {
            Tasks.setTimeout(() -> {
//                System.out.println("slidingRenew");
                expiration = curTime + slidingSpan;
            }, 100, this, Constants.TIMER_REPLACE_FLAG);
            return slidingSpan;
        }
        return ttl;
    }

    public boolean slidingRenew() {
        if (!isSliding()) {
            return false;
        }

        if ((expiration += slidingSpan) < 0) {
            expiration = Long.MAX_VALUE;
        }
        return true;
    }
}
