package org.rx.net.http.cookie;

import okhttp3.Cookie;
import org.rx.core.Linq;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class VolatileCookieStorage implements Iterable<Cookie> {
    final Set<IdentifiableCookie> store = ConcurrentHashMap.newKeySet();

    public void addAll(Collection<Cookie> newCookies) {
        store.addAll(IdentifiableCookie.decorateAll(newCookies));
    }

    public void clear() {
        store.clear();
    }

    @Override
    public Iterator<Cookie> iterator() {
        return Linq.from(store).select(p -> p.cookie).iterator();
    }
}
