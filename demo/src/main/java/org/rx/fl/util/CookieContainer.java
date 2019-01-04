package org.rx.fl.util;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.rx.NQuery;
import org.rx.fl.util.cookie.PersistentCookieJar;
import org.rx.fl.util.cookie.cache.SetCookieCache;
import org.rx.fl.util.cookie.persistence.MemoryCookiePersistor;

import java.util.*;

public final class CookieContainer implements CookieJar {
    private final PersistentCookieJar cookieJar = new PersistentCookieJar(new SetCookieCache(), new MemoryCookiePersistor());

    public void saveFromResponse(String url, Set<org.openqa.selenium.Cookie> cookieSet) {
        cookieJar.saveFromResponse(HttpUrl.get(url), NQuery.of(cookieSet).select(p -> {
            Cookie.Builder builder = new Cookie.Builder().name(p.getName()).value(p.getValue()).expiresAt(p.getExpiry().getTime()).domain(p.getDomain()).path(p.getPath());
            if (p.isSecure()) {
                builder.secure();
            }
            if (p.isHttpOnly()) {
                builder.httpOnly();
            }
            return builder.build();
        }).toList());
    }

    public Set<org.openqa.selenium.Cookie> loadForRequest(String url) {
        return NQuery.of(cookieJar.loadForRequest(HttpUrl.get(url)))
                .select(p -> new org.openqa.selenium.Cookie(p.name(), p.value(), p.domain(), p.path(), new Date(p.expiresAt()), p.secure(), p.httpOnly())).toSet();
    }

    @Override
    public void saveFromResponse(HttpUrl httpUrl, List<Cookie> list) {
        cookieJar.saveFromResponse(httpUrl, list);
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl httpUrl) {
        return cookieJar.loadForRequest(httpUrl);
    }
}
