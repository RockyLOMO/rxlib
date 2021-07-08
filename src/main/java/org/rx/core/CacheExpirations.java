package org.rx.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.bean.DateTime;

@RequiredArgsConstructor
@Getter
public final class CacheExpirations {
    public static final CacheExpirations NON_EXPIRE = new CacheExpirations(null, -1);
    public static final int ONE_DAY_EXPIRE = 60 * 24;

    public static CacheExpirations today() {
        return today(ONE_DAY_EXPIRE);
    }

    public static CacheExpirations today(int expireMinutes) {
        DateTime now = DateTime.now(), expire = now.addMinutes(expireMinutes);
        DateTime max = DateTime.valueOf(String.format("%s 23:59:59", now.toDateString()), DateTime.FORMATS.first());
        return new CacheExpirations(expire.before(max) ? expire : max, -1);
    }

    public static CacheExpirations absolute(int expireMinutes) {
        return new CacheExpirations(DateTime.utcNow().addMinutes(expireMinutes), -1);
    }

    public static CacheExpirations sliding(int expireMinutes) {
        return new CacheExpirations(null, expireMinutes);
    }

    private final DateTime absoluteExpiration;
    private final int slidingExpiration;
}
