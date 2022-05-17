package org.rx.core;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.rx.bean.DateTime;

import java.io.Serializable;

@Getter
@AllArgsConstructor
public class CachePolicy implements Serializable {
    private static final long serialVersionUID = 4378825072232415879L;

    public static CachePolicy today() {
        return today(Constants.ONE_DAY_TOTAL_SECONDS);
    }

    public static CachePolicy today(int expireSeconds) {
        DateTime now = DateTime.now(), expire = now.addSeconds(expireSeconds);
        DateTime max = DateTime.valueOf(String.format("%s 23:59:59", now.toDateString()), DateTime.FORMATS.first());
        return new CachePolicy((expire.before(max) ? expire : max).getTime(), Constants.NON_EXPIRE);
    }

    public static CachePolicy absolute(int expireSeconds) {
        return new CachePolicy(DateTime.now().addSeconds(expireSeconds).getTime(), Constants.NON_EXPIRE);
    }

    public static CachePolicy sliding(int expireSeconds) {
        return new CachePolicy(Constants.NON_EXPIRE, expireSeconds * 1000L);
    }

    protected long absoluteExpiration = Constants.NON_EXPIRE;
    protected long slidingExpiration = Constants.NON_EXPIRE;

    public long getExpiration() {
        return absoluteExpiration != Constants.NON_EXPIRE ? absoluteExpiration : slidingExpiration;
    }

    public boolean hasExpiration() {
        return getExpiration() > Constants.NON_EXPIRE;
    }

    protected CachePolicy(CachePolicy policy) {
        if (policy == null) {
            return;
        }
        this.absoluteExpiration = policy.absoluteExpiration;
        this.slidingExpiration = policy.slidingExpiration;
    }
}
