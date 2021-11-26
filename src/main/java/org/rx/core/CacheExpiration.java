package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.rx.bean.DateTime;

import java.util.Objects;

@RequiredArgsConstructor
@Getter
//@EqualsAndHashCode
@ToString
public final class CacheExpiration {
    public static final CacheExpiration NON_EXPIRE = new CacheExpiration(0, null, -1);

    public static CacheExpiration today() {
        return today(Constants.ONE_DAY_EXPIRE_SECONDS);
    }

    public static CacheExpiration today(int expireSeconds) {
        DateTime now = DateTime.now(), expire = now.addSeconds(expireSeconds);
        DateTime max = DateTime.valueOf(String.format("%s 23:59:59", now.toDateString()), DateTime.FORMATS.first());
        return new CacheExpiration(0, (expire.before(max) ? expire : max).asUniversalTime(), NON_EXPIRE.slidingExpiration);
    }

    public static CacheExpiration absolute(int expireSeconds) {
        return new CacheExpiration(expireSeconds, DateTime.utcNow().addSeconds(expireSeconds), NON_EXPIRE.slidingExpiration);
    }

    public static CacheExpiration sliding(int expireSeconds) {
        return new CacheExpiration(NON_EXPIRE.absoluteTTL, NON_EXPIRE.absoluteExpiration, expireSeconds);
    }

    private final int absoluteTTL;
    private final DateTime absoluteExpiration;
    private final int slidingExpiration;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheExpiration that = (CacheExpiration) o;
        return absoluteTTL == that.absoluteTTL && slidingExpiration == that.slidingExpiration;
    }

    @Override
    public int hashCode() {
        return Objects.hash(absoluteTTL, slidingExpiration);
    }
}
