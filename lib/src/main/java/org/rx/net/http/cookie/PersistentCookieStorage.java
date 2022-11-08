package org.rx.net.http.cookie;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Cookie;

public class PersistentCookieStorage {
    static String createCookieKey(Cookie cookie) {
        return (cookie.secure() ? "https" : "http") + "://" + cookie.domain() + cookie.path() + "|" + cookie.name();
    }

    final Map<String, Cookie> store = new ConcurrentHashMap<>();

    public List<Cookie> loadAll() {
        return new ArrayList<>(store.values());
    }

    /**
     * Persist all cookies, existing cookies will be overwritten.
     *
     * @param cookies cookies persist
     */
    public void saveAll(Collection<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            store.put(createCookieKey(cookie), cookie);
        }
    }

    /**
     * Removes indicated cookies from persistence.
     *
     * @param cookies cookies to remove from persistence
     */
    public void removeAll(Collection<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            store.remove(createCookieKey(cookie));
        }
    }

    public void clear() {
        store.clear();
    }
}
