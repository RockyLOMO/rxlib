package org.rx.core;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.rx.bean.DateTime;

import java.util.Objects;

@RequiredArgsConstructor
@Getter
//@EqualsAndHashCode
@ToString
public final class CacheExpirations {
    public static final CacheExpirations NON_EXPIRE = new CacheExpirations(0, null, -1);
    public static final int ONE_DAY_EXPIRE = 60 * 60 * 24;

    public static CacheExpirations today() {
        return today(ONE_DAY_EXPIRE);
    }

    public static CacheExpirations today(int expireSeconds) {
        DateTime now = DateTime.now(), expire = now.addSeconds(expireSeconds);
        DateTime max = DateTime.valueOf(String.format("%s 23:59:59", now.toDateString()), DateTime.FORMATS.first());
        return new CacheExpirations(0, (expire.before(max) ? expire : max).asUniversalTime(), NON_EXPIRE.slidingExpiration);
    }

    public static CacheExpirations absolute(int expireSeconds) {
        return new CacheExpirations(expireSeconds, DateTime.utcNow().addSeconds(expireSeconds), NON_EXPIRE.slidingExpiration);
    }

    public static CacheExpirations sliding(int expireSeconds) {
        return new CacheExpirations(NON_EXPIRE.absoluteTTL, NON_EXPIRE.absoluteExpiration, expireSeconds);
    }

    private final int absoluteTTL;
    private final DateTime absoluteExpiration;
    private final int slidingExpiration;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheExpirations that = (CacheExpirations) o;
        return absoluteTTL == that.absoluteTTL && slidingExpiration == that.slidingExpiration;
    }

    @Override
    public int hashCode() {
        return Objects.hash(absoluteTTL, slidingExpiration);
    }
}
