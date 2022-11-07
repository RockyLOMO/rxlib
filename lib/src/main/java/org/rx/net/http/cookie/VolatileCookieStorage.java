package org.rx.net.http.cookie;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Cookie;
import org.rx.core.Linq;

public class VolatileCookieStorage implements Iterable<Cookie> {
    final Set<IdentifiableCookie> cookies = ConcurrentHashMap.newKeySet();

    public void addAll(Collection<Cookie> newCookies) {
        cookies.addAll(IdentifiableCookie.decorateAll(newCookies));
    }

    public void clear() {
        cookies.clear();
    }

    @Override
    public Iterator<Cookie> iterator() {
        return Linq.from(cookies).select(p -> p.cookie).iterator();
    }
}
