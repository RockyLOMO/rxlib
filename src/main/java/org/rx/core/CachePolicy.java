package org.rx.core;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.rx.bean.DateTime;

import java.io.Serializable;

@Getter
@AllArgsConstructor
public class CachePolicy implements Serializable {
    public static final CachePolicy NON_EXPIRE = new CachePolicy(null, -1);

    public static CachePolicy today() {
        return today(Constants.ONE_DAY_TOTAL_SECONDS);
    }

    public static CachePolicy today(int expireSeconds) {
        DateTime now = DateTime.now(), expire = now.addSeconds(expireSeconds);
        DateTime max = DateTime.valueOf(String.format("%s 23:59:59", now.toDateString()), DateTime.FORMATS.first());
        return new CachePolicy((expire.before(max) ? expire : max), NON_EXPIRE.slidingExpiration);
    }

    public static CachePolicy absolute(int expireSeconds) {
        return new CachePolicy(DateTime.now().addSeconds(expireSeconds), NON_EXPIRE.slidingExpiration);
    }

    public static CachePolicy sliding(int expireSeconds) {
        return new CachePolicy(NON_EXPIRE.absoluteExpiration, expireSeconds * 1000L);
    }

    protected DateTime absoluteExpiration;
    protected long slidingExpiration;

    protected CachePolicy(CachePolicy policy) {
        this.absoluteExpiration = policy.absoluteExpiration;
        this.slidingExpiration = policy.slidingExpiration;
    }
}
