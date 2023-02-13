package org.rx.net.http.cookie;

import okhttp3.Cookie;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
