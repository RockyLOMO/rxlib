package org.rx.net.http.cookie;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import okhttp3.Cookie;

public class MemoryCookieCache implements CookieCache {
    final Set<IdentifiableCookie> cookies = new HashSet<>();

    @Override
    public void addAll(Collection<Cookie> newCookies) {
        for (IdentifiableCookie cookie : IdentifiableCookie.decorateAll(newCookies)) {
            cookies.remove(cookie);
            cookies.add(cookie);
        }
    }

    @Override
    public void clear() {
        cookies.clear();
    }

    @Override
    public Iterator<Cookie> iterator() {
        return new SetCookieCacheIterator();
    }

    class SetCookieCacheIterator implements Iterator<Cookie> {
        final Iterator<IdentifiableCookie> iterator = cookies.iterator();

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public Cookie next() {
            return iterator.next().getCookie();
        }

        @Override
        public void remove() {
            iterator.remove();
        }
    }
}
