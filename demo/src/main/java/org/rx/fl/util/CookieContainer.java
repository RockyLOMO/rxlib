package org.rx.fl.util;

import lombok.SneakyThrows;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.rx.NQuery;
import org.rx.fl.util.cookie.PersistentCookieJar;
import org.rx.fl.util.cookie.cache.SetCookieCache;
import org.rx.fl.util.cookie.persistence.MemoryCookiePersistor;

import java.lang.reflect.Field;
import java.util.*;

import static org.rx.Contract.require;

public final class CookieContainer implements CookieJar {
    private final PersistentCookieJar cookieJar = new PersistentCookieJar(new SetCookieCache(), new MemoryCookiePersistor());

    public void saveFromResponse(String url, Set<org.openqa.selenium.Cookie> cookieSet) {
        require(url, cookieSet);

        cookieJar.saveFromResponse(HttpUrl.get(url), NQuery.of(cookieSet).select(p -> {
            String domain = p.getDomain();
            if (domain.startsWith(".")) {
                domain = domain.substring(1);
            }
            Cookie.Builder builder = new Cookie.Builder().name(p.getName()).value(p.getValue())
                    .domain(domain)
                    .path(p.getPath());
            if (p.getExpiry() != null) {
                builder = builder.expiresAt(p.getExpiry().getTime());
            }
            if (p.isSecure()) {
                builder = builder.secure();
            }
            if (p.isHttpOnly()) {
                builder = builder.httpOnly();
            }
            return fill(builder.build(), p.getDomain());
        }).toList());
    }

    @SneakyThrows
    private Cookie fill(Cookie cookie, String domain) {
//        Field field = Cookie.class.getDeclaredField("domain");
//        field.setAccessible(true);
//        field.set(cookie, domain);
        return cookie;
    }

    public Set<org.openqa.selenium.Cookie> loadForRequest(String url) {
        require(url);

        return NQuery.of(cookieJar.loadForRequest(HttpUrl.get(url)))
                .select(p -> new org.openqa.selenium.Cookie(p.name(), p.value(), p.domain(), p.path(), new Date(p.expiresAt()), p.secure(), p.httpOnly())).toSet();
    }

    @Override
    public void saveFromResponse(HttpUrl httpUrl, List<Cookie> list) {
        require(httpUrl, list);

        cookieJar.saveFromResponse(httpUrl, list);
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl httpUrl) {
        require(httpUrl);

        return cookieJar.loadForRequest(httpUrl);
    }
}
