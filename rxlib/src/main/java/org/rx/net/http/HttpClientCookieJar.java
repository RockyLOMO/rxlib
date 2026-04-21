package org.rx.net.http;

import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import lombok.Getter;
import org.rx.annotation.DbColumn;
import org.rx.core.Strings;
import org.rx.io.EntityDatabase;
import org.rx.io.EntityQueryLambda;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public final class HttpClientCookieJar {
    public static final HttpClientCookieJar COOKIES = new HttpClientCookieJar();

    final HttpClientCookieStorage storage;
    final CopyOnWriteArrayList<StoredCookie> cookies;

    public HttpClientCookieJar() {
        this(new MemoryCookieStorage());
    }

    public HttpClientCookieJar(HttpClientCookieStorage storage) {
        this.storage = storage != null ? storage : new MemoryCookieStorage();
        cookies = new CopyOnWriteArrayList<>(this.storage.loadAll());
    }

    public static HttpClientCookieJar memory() {
        return new HttpClientCookieJar(new MemoryCookieStorage());
    }

    public static HttpClientCookieJar h2(EntityDatabase db) {
        return new HttpClientCookieJar(new H2CookieStorage(db));
    }

    public String loadForRequest(URI uri) {
        long now = System.currentTimeMillis();
        List<Cookie> matched = new ArrayList<>();
        for (StoredCookie stored : cookies) {
            if (stored.isExpired(now)) {
                cookies.remove(stored);
                storage.remove(stored);
                continue;
            }
            if (stored.matches(uri)) {
                matched.add(stored.cookie());
            }
        }
        return matched.isEmpty() ? null : ClientCookieEncoder.STRICT.encode(matched);
    }

    public void saveFromResponse(URI uri, List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) {
            return;
        }
        for (String raw : setCookies) {
            Cookie cookie = ClientCookieDecoder.STRICT.decode(raw);
            if (cookie != null) {
                save(uri, cookie);
            }
        }
    }

    public void saveRawCookie(URI uri, String rawCookie) {
        if (Strings.isEmpty(rawCookie)) {
            return;
        }
        for (String pair : Strings.split(rawCookie, ";")) {
            int i = pair.indexOf("=");
            if (i <= 0) {
                continue;
            }
            DefaultCookie cookie = new DefaultCookie(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
            save(uri, cookie);
        }
    }

    void save(URI uri, Cookie cookie) {
        boolean hostOnly = Strings.isEmpty(cookie.domain());
        if (Strings.isEmpty(cookie.domain())) {
            cookie.setDomain(uri.getHost());
        }
        if (Strings.isEmpty(cookie.path())) {
            cookie.setPath("/");
        }
        StoredCookie stored = new StoredCookie(cookie, hostOnly);
        cookies.remove(stored);
        if (stored.isExpired(System.currentTimeMillis())) {
            storage.remove(stored);
            return;
        }
        cookies.add(stored);
        storage.save(stored);
    }

    public void clearSession() {
        long now = System.currentTimeMillis();
        for (StoredCookie cookie : cookies) {
            if (cookie.isSession() || cookie.isExpired(now)) {
                cookies.remove(cookie);
                storage.remove(cookie);
            }
        }
    }

    public void clear() {
        cookies.clear();
        storage.clear();
    }

    public interface HttpClientCookieStorage {
        List<StoredCookie> loadAll();

        void save(StoredCookie cookie);

        void remove(StoredCookie cookie);

        void clear();
    }

    public static final class MemoryCookieStorage implements HttpClientCookieStorage {
        final CopyOnWriteArrayList<StoredCookie> store = new CopyOnWriteArrayList<>();

        @Override
        public List<StoredCookie> loadAll() {
            return new ArrayList<>(store);
        }

        @Override
        public void save(StoredCookie cookie) {
            store.remove(cookie);
            store.add(cookie);
        }

        @Override
        public void remove(StoredCookie cookie) {
            store.remove(cookie);
        }

        @Override
        public void clear() {
            store.clear();
        }
    }

    public static final class H2CookieStorage implements HttpClientCookieStorage {
        final EntityDatabase db;

        public H2CookieStorage(EntityDatabase db) {
            this.db = db != null ? db : EntityDatabase.DEFAULT;
            this.db.createMapping(StoredCookie.class);
        }

        @Override
        public List<StoredCookie> loadAll() {
            long now = System.currentTimeMillis();
            List<StoredCookie> result = new ArrayList<>();
            List<StoredCookie> entities = db.findBy(new EntityQueryLambda<>(StoredCookie.class));
            for (StoredCookie cookie : entities) {
                String storedId = cookie.id;
                if (!cookie.isValid() || cookie.isExpired(now)) {
                    db.deleteById(StoredCookie.class, storedId);
                    continue;
                }
                if (!Objects.equals(storedId, cookie.id)) {
                    // 兼容旧主键格式，冷启动加载时迁移一次，避免重复持久化记录。
                    db.deleteById(StoredCookie.class, storedId);
                    db.save(cookie, true);
                }
                result.add(cookie);
            }
            return result;
        }

        @Override
        public void save(StoredCookie cookie) {
            if (cookie.isSession()) {
                remove(cookie);
                return;
            }
            db.save(cookie, true);
        }

        @Override
        public void remove(StoredCookie cookie) {
            db.deleteById(StoredCookie.class, StoredCookie.id(cookie.name, cookie.domain, cookie.path, cookie.isSecure(), cookie.isHostOnly()));
            db.deleteById(StoredCookie.class, StoredCookie.legacyId(cookie.name, cookie.domain, cookie.path));
        }

        @Override
        public void clear() {
            db.truncateMapping(StoredCookie.class);
        }
    }

    @Getter
    public static final class StoredCookie implements Serializable {
        private static final long serialVersionUID = -1714170629027078331L;

        @DbColumn(primaryKey = true, length = 512)
        public String id;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC, length = 255)
        public String domain;
        public String path;
        public String name;
        public String value;
        public Boolean secure;
        public Boolean hostOnly;
        public Boolean httpOnly;
        @DbColumn(index = DbColumn.IndexKind.INDEX_ASC)
        public Long expiresAt;
        public Boolean session;
        transient Cookie cookie;

        public static String id(String name, String domain, String path, boolean secure, boolean hostOnly) {
            return (domain == null ? Strings.EMPTY : domain.toLowerCase(Locale.ENGLISH))
                    + "|" + (path == null ? "/" : path)
                    + "|" + name
                    + "|" + (secure ? "1" : "0")
                    + "|" + (hostOnly ? "1" : "0");
        }

        static String legacyId(String name, String domain, String path) {
            return (domain == null ? Strings.EMPTY : domain.toLowerCase(Locale.ENGLISH)) + "|" + (path == null ? "/" : path) + "|" + name;
        }

        public StoredCookie() {
        }

        StoredCookie(Cookie cookie) {
            this(cookie, false, cookie.maxAge() == Long.MIN_VALUE,
                    cookie.maxAge() == Long.MIN_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cookie.maxAge()));
        }

        StoredCookie(Cookie cookie, boolean hostOnly) {
            this(cookie, hostOnly, cookie.maxAge() == Long.MIN_VALUE,
                    cookie.maxAge() == Long.MIN_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(cookie.maxAge()));
        }

        StoredCookie(Cookie cookie, boolean hostOnly, boolean session, long expiresAt) {
            this.cookie = cookie;
            name = cookie.name();
            domain = cookie.domain() != null ? cookie.domain().toLowerCase(Locale.ENGLISH) : Strings.EMPTY;
            path = cookie.path() != null ? cookie.path() : "/";
            value = cookie.value();
            secure = cookie.isSecure();
            this.hostOnly = hostOnly;
            httpOnly = cookie.isHttpOnly();
            this.session = session;
            this.expiresAt = expiresAt;
            id = id(name, domain, path, isSecure(), isHostOnly());
        }

        Cookie cookie() {
            Cookie c = cookie;
            if (c != null) {
                return c;
            }
            DefaultCookie dc = new DefaultCookie(name, value != null ? value : Strings.EMPTY);
            dc.setDomain(domain);
            dc.setPath(path);
            dc.setSecure(Boolean.TRUE.equals(secure));
            dc.setHttpOnly(Boolean.TRUE.equals(httpOnly));
            if (!Boolean.TRUE.equals(session) && expiresAt != null && expiresAt != Long.MAX_VALUE) {
                long seconds = Math.max(1L, (expiresAt - System.currentTimeMillis() + 999L) / 1000L);
                dc.setMaxAge(seconds);
            }
            cookie = dc;
            return dc;
        }

        public Cookie getCookie() {
            return cookie();
        }

        boolean isValid() {
            if (Strings.isEmpty(name) || Strings.isEmpty(domain) || Strings.isEmpty(path) || expiresAt == null) {
                return false;
            }
            if (session == null) {
                session = false;
            }
            if (secure == null) {
                secure = false;
            }
            if (hostOnly == null) {
                hostOnly = false;
            }
            id = id(name, domain, path, isSecure(), isHostOnly());
            return true;
        }

        boolean isExpired(long now) {
            return expiresAt <= now;
        }

        public boolean isSession() {
            return Boolean.TRUE.equals(session);
        }

        public boolean isSecure() {
            return Boolean.TRUE.equals(secure);
        }

        public boolean isHostOnly() {
            return Boolean.TRUE.equals(hostOnly);
        }

        public boolean isHttpOnly() {
            return Boolean.TRUE.equals(httpOnly);
        }

        boolean matches(URI uri) {
            if (isSecure() && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ENGLISH);
            if (isHostOnly() ? !host.equals(domain) : (!host.equals(domain) && !host.endsWith("." + domain))) {
                return false;
            }
            String rawPath = uri.getRawPath();
            if (Strings.isEmpty(rawPath)) {
                rawPath = "/";
            }
            return rawPath.startsWith(path);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof StoredCookie)) {
                return false;
            }
            StoredCookie that = (StoredCookie) o;
            return name.equals(that.name)
                    && domain.equals(that.domain)
                    && path.equals(that.path)
                    && isSecure() == that.isSecure()
                    && isHostOnly() == that.isHostOnly();
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + domain.hashCode();
            result = 31 * result + path.hashCode();
            result = 31 * result + (isSecure() ? 1 : 0);
            result = 31 * result + (isHostOnly() ? 1 : 0);
            return result;
        }
    }
}
