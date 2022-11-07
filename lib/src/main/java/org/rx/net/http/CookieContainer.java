package org.rx.net.http;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.rx.core.Linq;
import org.rx.net.http.cookie.VolatileCookieStorage;
import org.rx.net.http.cookie.CookiePersistor;
import org.rx.net.http.cookie.MemoryCookiePersistor;

public final class CookieContainer implements CookieJar {
    static boolean isCookieExpired(Cookie cookie) {
        return cookie.expiresAt() < System.currentTimeMillis();
    }

    final VolatileCookieStorage volatileStorage;
    final CookiePersistor persistor;

    public CookieContainer() {
        this(new VolatileCookieStorage(), new MemoryCookiePersistor());
    }

    public CookieContainer(VolatileCookieStorage cache, CookiePersistor persistor) {
        this.volatileStorage = cache;
        this.persistor = persistor;

        this.volatileStorage.addAll(persistor.loadAll());
    }

    @Override
    public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        volatileStorage.addAll(cookies);
        persistor.saveAll(Linq.from(cookies).where(Cookie::persistent).toList());
    }

    @Override
    public synchronized List<Cookie> loadForRequest(HttpUrl url) {
        List<Cookie> cookiesToRemove = new ArrayList<>();
        List<Cookie> validCookies = new ArrayList<>();

        for (Iterator<Cookie> it = volatileStorage.iterator(); it.hasNext(); ) {
            Cookie currentCookie = it.next();
            if (isCookieExpired(currentCookie)) {
                cookiesToRemove.add(currentCookie);
                it.remove();
            } else if (currentCookie.matches(url)) {
                validCookies.add(currentCookie);
            }
        }

        persistor.removeAll(cookiesToRemove);
        return validCookies;
    }

    public synchronized void clearSession() {
        volatileStorage.clear();
        volatileStorage.addAll(persistor.loadAll());
    }

    public synchronized void clear() {
        volatileStorage.clear();
        persistor.clear();
    }
}
