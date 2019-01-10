package org.rx.fl.util.cookie.persistence;

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
//        List<Cookie> cookies = new ArrayList<>(sharedPreferences.getAll().size());
//        for (Map.Entry<String, ?> entry : sharedPreferences.getAll().entrySet()) {
//            String serializedCookie = (String) entry.getValue();
//            Cookie cookie = new SerializableCookie().decode(serializedCookie);
//            if (cookie != null) {
//                cookies.add(cookie);
//            }
//        }
//        return cookies;
    }

    @Override
    public void saveAll(Collection<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            store.put(createCookieKey(cookie), cookie);
        }
//        SharedPreferences.Editor editor = sharedPreferences.edit();
//        for (Cookie cookie : cookies) {
//            editor.putString(createCookieKey(cookie), new SerializableCookie().encode(cookie));
//        }
//        editor.commit();
    }

    @Override
    public void removeAll(Collection<Cookie> cookies) {
        for (Cookie cookie : cookies) {
            store.remove(createCookieKey(cookie));
        }
//        SharedPreferences.Editor editor = sharedPreferences.edit();
//        for (Cookie cookie : cookies) {
//            editor.remove(createCookieKey(cookie));
//        }
//        editor.commit();
    }

    @Override
    public void clear() {
        store.clear();
    }
}
