package org.rx.net.http.cookie;

import java.util.*;

import okhttp3.Cookie;

public class MemoryCookiePersistor implements CookiePersistor {
    static String createCookieKey(Cookie cookie) {
        return (cookie.secure() ? "https" : "http") + "://" + cookie.domain() + cookie.path() + "|" + cookie.name();
    }

    final Map<String, Cookie> store = new HashMap<>();

    @Override
    public List<Cookie> loadAll() {
        return new ArrayList<>(store.values());
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
