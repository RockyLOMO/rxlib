package org.rx.net.http;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.rx.core.Linq;
import org.rx.net.http.cookie.VolatileCookieStorage;
import org.rx.net.http.cookie.PersistentCookieStorage;

public final class CookieContainer implements CookieJar {
    static boolean isCookieExpired(Cookie cookie) {
        return cookie.expiresAt() < System.currentTimeMillis();
    }

    final VolatileCookieStorage volatileStorage;
    final PersistentCookieStorage persistentStorage;

    public CookieContainer() {
        this(new VolatileCookieStorage(), new PersistentCookieStorage());
    }

    public CookieContainer(VolatileCookieStorage cache, PersistentCookieStorage persistentStorage) {
        this.volatileStorage = cache;
        this.persistentStorage = persistentStorage;

        this.volatileStorage.addAll(persistentStorage.loadAll());
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
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

        persistentStorage.removeAll(cookiesToRemove);
        return validCookies;
    }

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
        volatileStorage.addAll(cookies);
        persistentStorage.saveAll(Linq.from(cookies).where(Cookie::persistent).toList());
    }

    public synchronized void clearSession() {
        volatileStorage.clear();
        volatileStorage.addAll(persistentStorage.loadAll());
    }

    public synchronized void clear() {
        volatileStorage.clear();
        persistentStorage.clear();
    }
}
