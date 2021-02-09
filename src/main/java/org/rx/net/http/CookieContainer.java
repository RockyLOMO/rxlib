package org.rx.net.http;

import lombok.Getter;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import org.rx.net.http.cookie.PersistentCookieJar;
import org.rx.net.http.cookie.cache.SetCookieCache;
import org.rx.net.http.cookie.persistence.MemoryCookiePersistor;

import java.util.*;

import static org.rx.core.App.require;

public final class CookieContainer implements CookieJar {
    @Getter
    private final PersistentCookieJar cookieJar = new PersistentCookieJar(new SetCookieCache(), new MemoryCookiePersistor());

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
