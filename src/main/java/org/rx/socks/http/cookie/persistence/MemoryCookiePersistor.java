package org.rx.socks.http.cookie.persistence;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Cookie;
import org.rx.common.NQuery;

public class MemoryCookiePersistor implements CookiePersistor {
    private static String createCookieKey(Cookie cookie) {
        return (cookie.secure() ? "https" : "http") + "://" + cookie.domain() + cookie.path() + "|" + cookie.name();
    }

    private final ConcurrentHashMap<String, Cookie> store;

    public MemoryCookiePersistor() {
        store = new ConcurrentHashMap<>();
    }

    @Override
    public List<Cookie> loadAll() {
        return NQuery.of(store.values()).toList();
    }

    @Override
    public void saveAll(Collection<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            store.put(createCookieKey(cookie), cookie);
        }
    }

    @Override
    public void removeAll(Collection<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            store.remove(createCookieKey(cookie));
        }
    }

    @Override
    public void clear() {
        store.clear();
    }
}
