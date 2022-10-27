package org.rx.net.http.cookie;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import okhttp3.Cookie;

/**
 * This class decorates a Cookie to re-implements equals() and hashcode() methods in order to identify
 * the cookie by the following attributes: name, domain, path, secure & hostOnly.<p>
 * <p>
 * This new behaviour will be useful in determining when an already existing cookie in session must be overwritten.
 */
@RequiredArgsConstructor
@Getter
class IdentifiableCookie {
    final Cookie cookie;

    static List<IdentifiableCookie> decorateAll(Collection<Cookie> cookies) {
        List<IdentifiableCookie> identifiableCookies = new ArrayList<>(cookies.size());
        for (Cookie cookie : cookies) {
            identifiableCookies.add(new IdentifiableCookie(cookie));
        }
        return identifiableCookies;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof IdentifiableCookie)) return false;
        IdentifiableCookie that = (IdentifiableCookie) other;
        return that.cookie.name().equals(this.cookie.name())
                && that.cookie.domain().equals(this.cookie.domain())
                && that.cookie.path().equals(this.cookie.path())
                && that.cookie.secure() == this.cookie.secure()
                && that.cookie.hostOnly() == this.cookie.hostOnly();
    }

    @Override
    public int hashCode() {
        int hash = 17;
        hash = 31 * hash + cookie.name().hashCode();
        hash = 31 * hash + cookie.domain().hashCode();
        hash = 31 * hash + cookie.path().hashCode();
        hash = 31 * hash + (cookie.secure() ? 0 : 1);
        hash = 31 * hash + (cookie.hostOnly() ? 0 : 1);
        return hash;
    }
}
