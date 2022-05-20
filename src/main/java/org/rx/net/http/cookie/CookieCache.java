package org.rx.net.http.cookie;

import java.util.Collection;

import okhttp3.Cookie;

/**
 * A CookieCache handles the volatile cookie session storage.
 */
public interface CookieCache extends Iterable<Cookie> {
    void addAll(Collection<Cookie> cookies);

    void clear();
}
